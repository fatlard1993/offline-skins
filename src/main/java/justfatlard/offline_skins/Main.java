package justfatlard.offline_skins;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
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
		if (justfatlard.pandorical.api.PandoricalApi.isAvailable()) SkinConfig.menu();

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			dress(handler.getPlayer()));

		// Heads on walls belong to people who are not here. Everyone the folders already know is
		// dressed before the first player arrives, so a head is never Steve for want of its owner.
		ServerLifecycleEvents.SERVER_STARTED.register(Main::dressTheAbsent);

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
				wear(player.level().getServer(), player, found, from);
				LOGGER.info("Dressed {} from {}{}", name, from, found.slim() ? " (slim)" : "");
			});
		});
	}

	/**
	 * Hand a skin to Pandorical with the lifetime it deserves.
	 *
	 * <p>A face that lives on disk - the admin's file, or a fetch kept forever - is kept for the
	 * server's life too, so the player's head still wears it after they leave. One kept only for
	 * the session, or not at all, goes when they do, which is what those settings asked for.
	 */
	private static void wear(MinecraftServer server, ServerPlayer player, SkinSource.Skin skin,
			String origin) {
		boolean onDisk = origin.equals("file") || SkinConfig.cache() == SkinConfig.Cache.FOREVER;

		if (onDisk) {
			PandoricalApi.skins().set(server, player.getUUID(), skin.png(), skin.slim());
		} else {
			PandoricalApi.skins().set(player, skin.png(), skin.slim());
		}
	}

	/** Dress everyone the folders have a face for, whether or not they ever come back. */
	private static void dressTheAbsent(MinecraftServer server) {
		LOOKUP.execute(() -> {
			// Case-insensitive on the name, with the file's own spelling kept: the admin's folder
			// last, so a file put there wins over a cached fetch of the same person.
			java.util.Map<String, java.util.Map.Entry<String, SkinSource.Skin>> known =
				new java.util.LinkedHashMap<>();

			if (SkinConfig.cache() == SkinConfig.Cache.FOREVER) {
				SkinSource.allIn(SkinConfig.cacheDir()).forEach((name, skin) ->
					known.put(name.toLowerCase(java.util.Locale.ROOT), java.util.Map.entry(name, skin)));
			}
			SkinSource.allIn(SkinConfig.skinsDir()).forEach((name, skin) ->
				known.put(name.toLowerCase(java.util.Locale.ROOT), java.util.Map.entry(name, skin)));
			if (known.isEmpty()) return;

			server.execute(() -> {
				for (var entry : known.values()) {
					PandoricalApi.skins().set(server, idOf(server, entry.getKey()),
						entry.getValue().png(), entry.getValue().slim());
				}
				LOGGER.info("Dressed {} absent player(s) from the skin folders", known.size());
			});
		});
	}

	/**
	 * The UUID a name's profile carries here.
	 *
	 * <p>Asked of the server's own name cache first, which remembers the spelling every player
	 * joined under; an offline UUID is made from that spelling, so a lowercased name would be a
	 * different person. A name the server has never seen is answered the way the server itself
	 * would answer it on a join.
	 */
	private static java.util.UUID idOf(MinecraftServer server, String name) {
		return server.services().nameToIdCache().get(name).map(NameAndId::id)
			.orElseGet(() -> net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name));
	}
}
