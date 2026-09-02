package justfatlard.offline_skins;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives offline-mode players a face.
 *
 * <p>An offline server mints its own UUIDs, so nobody's profile carries textures and everybody is
 * Steve. This dresses them: a PNG named after the player if the server has one, and otherwise the
 * skin that name really owns, fetched from Mojang.
 *
 * <p>Both answers are delivered as the image itself, through Pandorical. That is not a preference -
 * it is the only route that works. A client resolves a profile's skin through authlib, which checks
 * the texture URL against a list of domains it fetches from Mojang, so a server cannot put its own
 * file into that answer no matter how it is phrased. Handing over the picture goes around the
 * question entirely, and it means the file case and the Mojang case travel the same way instead of
 * being two mechanisms with two sets of failures.
 */
public class Main implements ModInitializer {
	public static final String MOD_ID = "offline-skins-justfatlard";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * One thread, off the server's. Looking a skin up can mean two round trips to Mojang and a
	 * download, and a join must not sit and wait for someone else's servers to answer.
	 */
	private static final ExecutorService LOOKUP = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "offline-skins-lookup");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * Skins fetched this session, for the policy that keeps them only while the player is here.
	 *
	 * <p>Empty and unused under the other two: {@code forever} keeps them on disk instead, and
	 * {@code off} keeps nothing anywhere.
	 */
	private static final java.util.Map<String, SkinSource.Skin> SESSION =
		new java.util.concurrent.ConcurrentHashMap<>();

	@Override
	public void onInitialize() {
		SkinConfig.load();

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			dress(handler.getPlayer()));

		// The whole of what "no accumulation" means: the entry goes when its player does. Under
		// the other policies there is nothing here to drop.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			SESSION.remove(handler.getPlayer().getGameProfile().name()
				.toLowerCase(java.util.Locale.ROOT)));

		LOGGER.info("[{}] Loaded - skins from {}{}, cache {}", MOD_ID, SkinConfig.skinsDir(),
			SkinConfig.mojangFallback() ? ", falling back to Mojang" : ", files only",
			SkinConfig.cache().name().toLowerCase(java.util.Locale.ROOT));
	}

	private static void dress(ServerPlayer player) {
		String name = player.getGameProfile().name();

		// The file is read on the lookup thread too. It is the fast path, but it is still disk,
		// and the reason for the thread is to keep every kind of waiting off the server's.
		LOOKUP.execute(() -> {
			// The admin's own folder first, always and uncached: a file put there is a decision,
			// and it should take effect on the next join rather than waiting out a cache.
			SkinSource.Skin skin = SkinSource.fromFile(name);
			String origin = "file";

			if (skin == null) {
				skin = SESSION.get(name.toLowerCase(java.util.Locale.ROOT));
				origin = "cache";
			}
			if (skin == null) {
				skin = SkinSource.fromCache(name);
				origin = "cache";
			}
			if (skin == null && SkinConfig.mojangFallback()) {
				skin = SkinSource.fromMojang(name);
				origin = "mojang";

				if (skin != null) {
					SkinSource.cache(name, skin);
					if (SkinConfig.cache() == SkinConfig.Cache.SESSION) {
						SESSION.put(name.toLowerCase(java.util.Locale.ROOT), skin);
					}
				}
			}
			if (skin == null || skin.png() == null || skin.png().length == 0) return;

			SkinSource.Skin found = skin;
			String from = origin;

			// Back onto the server thread to hand it out: the player list and the packets that
			// reach it are the server's, and a background thread has no business touching either.
			player.level().getServer().execute(() -> {
				if (player.hasDisconnected()) return;
				PandoricalApi.skins().set(player, found.png(), found.slim());
				LOGGER.info("Dressed {} from {}{}", name, from, found.slim() ? " (slim)" : "");
			});
		});
	}
}
