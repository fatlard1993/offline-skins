package justfatlard.offline_skins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Where skins come from, and whether to go looking when there is no file.
 *
 * <p>Written out with its own explanation the first time it is missing, because the thing an admin
 * most needs to know - that a skin is a PNG named after the player, dropped in a folder - is not
 * something they should have to find in a readme.
 */
public final class SkinConfig {
	private static final String FILE = "offline-skins.properties";

	private static final String DEFAULTS = """
		# Offline Skins
		#
		# Put a 64x64 skin PNG in the skins folder named after the player: Steve.png
		# Names are matched without regard to case.
		#
		# For the three-pixel-arm model, name it Steve.slim.png instead.

		# When no file matches, look the player up by name and use the skin they really own.
		# Turn this off to use files only; a player without one keeps the default skin.
		mojang_fallback=true

		# Where the PNGs live, relative to the config directory.
		skins_directory=offline-skins

		# How long a skin fetched from Mojang is remembered. Files are always re-read, so a skin
		# you drop in the folder takes effect on the player's next join whatever this says.
		#
		#   forever - written to a cache folder and never fetched twice, across restarts too.
		#             Optimises for no refetches; costs a few KB on disk per player, forever.
		#   session - kept only while the player is connected. Optimises for no accumulation;
		#             costs one fetch per join.
		#   off     - fetched every time. For testing.
		skin_cache=forever
		""";

	/** What to do with a skin once it has been fetched. */
	public enum Cache {
		/** Kept on disk, so a restart does not send everyone back to Mojang. */
		FOREVER,
		/** Kept in memory until the player leaves. */
		SESSION,
		/** Not kept at all. */
		OFF
	}

	private static boolean mojangFallback = true;
	private static String skinsDirectory = "offline-skins";
	private static Cache cache = Cache.FOREVER;

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE);

		try {
			if (!Files.exists(path)) {
				Files.createDirectories(path.getParent());
				Files.writeString(path, DEFAULTS);
			}

			Properties properties = new Properties();
			try (var in = Files.newInputStream(path)) {
				properties.load(in);
			}
			mojangFallback = Boolean.parseBoolean(
				properties.getProperty("mojang_fallback", "true"));
			skinsDirectory = properties.getProperty("skins_directory", "offline-skins");
			cache = readCache(properties.getProperty("skin_cache", "forever"));
		} catch (IOException e) {
			Main.LOGGER.warn("Could not read {} - using defaults", FILE, e);
		}

		try {
			Files.createDirectories(skinsDir());
			if (cache == Cache.FOREVER) Files.createDirectories(cacheDir());
		} catch (IOException e) {
			Main.LOGGER.warn("Could not create the skins directory", e);
		}
	}

	private static Cache readCache(String value) {
		try {
			return Cache.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			Main.LOGGER.warn("Unknown skin_cache '{}' - using forever", value);
			return Cache.FOREVER;
		}
	}

	public static boolean mojangFallback() {
		return mojangFallback;
	}

	public static Cache cache() {
		return cache;
	}

	/**
	 * Where fetched skins are kept, beside the folder an admin puts their own in rather than inside
	 * it: a cached copy is not something anybody chose, and mixing the two would make the folder
	 * fill with faces nobody put there.
	 */
	public static Path cacheDir() {
		return skinsDir().resolveSibling(skinsDirectory + "-cache");
	}

	public static Path skinsDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(skinsDirectory);
	}
}
