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
	static final ExecutorService LOOKUP = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "offline-skins-lookup");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * Skins fetched this session, for the policy that keeps them only while the player is here, by
	 * the player wearing them and with the name they were looked up under.
	 *
	 * <p>Empty and unused under the other two: {@code forever} keeps them on disk instead, and
	 * {@code off} keeps nothing anywhere.
	 */
	private static final java.util.Map<java.util.UUID, Session> SESSION =
		new java.util.concurrent.ConcurrentHashMap<>();

	private record Session(String target, SkinSource.Skin skin) {}

	/** A skin and where it was found: {@code file}, {@code cache} or {@code mojang}. */
	public record Found(SkinSource.Skin skin, String origin) {}

	@Override
	public void onInitialize() {
		SkinConfig.load();
		SkinChoices.load();
		if (justfatlard.pandorical.api.PandoricalApi.isAvailable()) SkinConfig.menu();
		SkinPicker.register();

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			dress(handler.getPlayer()));

		// Heads on walls belong to people who are not here. Everyone the folders already know is
		// dressed before the first player arrives, so a head is never Steve for want of its owner.
		ServerLifecycleEvents.SERVER_STARTED.register(Main::dressTheAbsent);

		// The whole of what "no accumulation" means: the entry goes when its player does. Under
		// the other policies there is nothing here to drop.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			SESSION.remove(handler.getPlayer().getUUID());
			SkinPicker.forget(handler.getPlayer().getUUID());
		});

		LOGGER.info("[{}] Loaded - skins from {}{}, cache {}", MOD_ID, SkinConfig.skinsDir(),
			SkinConfig.mojangFallback() ? ", falling back to Mojang" : ", files only",
			SkinConfig.cache().name().toLowerCase(java.util.Locale.ROOT));
	}

	/** The skin this player picked, or their own name's when they picked none. */
	private static void dress(ServerPlayer player) {
		String name = player.getGameProfile().name();
		String chosen = SkinChoices.of(player.getUUID());
		String target = chosen != null ? chosen : name;

		// The file is read on the lookup thread too. It is the fast path, but it is still disk,
		// and the reason for the thread is to keep every kind of waiting off the server's.
		LOOKUP.execute(() -> {
			Found found = find(player.getUUID(), target);
			if (found == null) return;

			// Back onto the server thread to hand it out: the player list and the packets that
			// reach it are the server's, and a background thread has no business touching either.
			player.level().getServer().execute(() -> {
				if (player.hasDisconnected()) return;
				wear(player.level().getServer(), player, found, false);
				LOGGER.info("Dressed {} {}from {}{}", name, chosen != null ? "as " + chosen + " " : "",
					found.origin(), found.skin().slim() ? " (slim)" : "");
			});
		});
	}

	/**
	 * Put this player in the skin {@code name} owns, or back in their own for null.
	 *
	 * <p>The choice is kept only once a skin has been found for it: a name with no skin anywhere
	 * would otherwise leave them choosing nothing, and Steve, on every join after.
	 *
	 * @param done told on the server thread whether it worked
	 */
	public static void switchTo(ServerPlayer player, String name, java.util.function.Consumer<Boolean> done) {
		String own = player.getGameProfile().name();
		boolean backToOwn = name == null || name.equalsIgnoreCase(own);
		String target = backToOwn ? own : name;
		java.util.UUID id = player.getUUID();
		MinecraftServer server = player.level().getServer();

		LOOKUP.execute(() -> {
			Found found = find(id, target);
			server.execute(() -> {
				if (found == null && !backToOwn) {
					done.accept(false);
					return;
				}
				SkinChoices.set(id, backToOwn ? null : target);
				// Gone already, the choice still stands for the next join; a face handed out now
				// for somebody no longer here would be sent to every later arrival.
				if (player.hasDisconnected()) return;
				if (found != null) wear(server, player, found, true);
				else PandoricalApi.skins().clear(server, id);
				LOGGER.info("{} switched to {}", own, backToOwn ? "their own skin" : target + "'s skin");
				done.accept(true);
			});
		});
	}

	/**
	 * The skin {@code target} owns, for this player to wear, or null when there is none to be had.
	 * On the lookup thread only.
	 */
	private static Found find(java.util.UUID wearer, String target) {
		// The admin's own folder first, always and uncached: a file put there is a decision,
		// and it should take effect on the next join rather than waiting out a cache.
		SkinSource.Skin skin = SkinSource.fromFile(target);
		if (skin != null) return valid(skin, "file");

		Session session = SESSION.get(wearer);
		if (session != null && session.target().equalsIgnoreCase(target)) return valid(session.skin(), "cache");

		skin = SkinSource.fromCache(target);
		if (skin != null) return valid(skin, "cache");

		if (!SkinConfig.mojangFallback()) return null;
		skin = SkinSource.fromMojang(target);
		Found found = valid(skin, "mojang");
		if (found == null) return null;

		SkinSource.cache(target, skin);
		if (SkinConfig.cache() == SkinConfig.Cache.SESSION) SESSION.put(wearer, new Session(target, skin));
		return found;
	}

	private static Found valid(SkinSource.Skin skin, String origin) {
		return skin == null || skin.png() == null || skin.png().length == 0 ? null : new Found(skin, origin);
	}

	/**
	 * Hand a skin to Pandorical with the lifetime it deserves.
	 *
	 * <p>A face that lives on disk - the admin's file, or a fetch kept forever - is kept for the
	 * server's life too, so the player's head still wears it after they leave. One kept only for
	 * the session, or not at all, goes when they do, which is what those settings asked for.
	 *
	 * @param replacing a switch mid-session: a face kept for the server's life would otherwise
	 *                  outlast a session one put over it, and come back when they leave
	 */
	private static void wear(MinecraftServer server, ServerPlayer player, Found found, boolean replacing) {
		boolean onDisk = found.origin().equals("file") || SkinConfig.cache() == SkinConfig.Cache.FOREVER;
		SkinSource.Skin skin = found.skin();

		if (onDisk) {
			PandoricalApi.skins().set(server, player.getUUID(), skin.png(), skin.slim());
		} else {
			if (replacing) PandoricalApi.skins().clear(server, player.getUUID());
			PandoricalApi.skins().set(player, skin.png(), skin.slim());
		}
	}

	/** Dress everyone the folders have a face for, whether or not they ever come back. */
	private static void dressTheAbsent(MinecraftServer server) {
		LOOKUP.execute(() -> {
			java.util.Map<String, SkinSource.Named> known = SkinSource.library();

			// A player who picked a skin wears it on their head too, over their own name's. Only
			// what the folders hold: a choice kept nowhere on disk waits for its owner to join.
			java.util.Map<java.util.UUID, SkinSource.Skin> chosen = new java.util.LinkedHashMap<>();
			SkinChoices.all().forEach((player, target) -> {
				SkinSource.Named named = known.get(target.toLowerCase(java.util.Locale.ROOT));
				if (named != null) chosen.put(player, named.skin());
			});
			if (known.isEmpty()) return;

			server.execute(() -> {
				for (SkinSource.Named named : known.values()) {
					PandoricalApi.skins().set(server, idOf(server, named.name()),
						named.skin().png(), named.skin().slim());
				}
				chosen.forEach((player, skin) ->
					PandoricalApi.skins().set(server, player, skin.png(), skin.slim()));
				LOGGER.info("Dressed {} absent player(s) from the skin folders", known.size() + chosen.size());
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
