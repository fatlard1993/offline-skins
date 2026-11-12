package justfatlard.offline_skins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The skin each player picked for themselves, as the name whose skin they wear.
 *
 * <p>Kept by UUID rather than by name: on an offline server the UUID is what a head and a profile
 * carry, so a player head left behind can be dressed in the choice without its owner here.
 */
public final class SkinChoices {
	private SkinChoices() {}

	private static final String FILE = "offline-skins-choices.properties";
	private static final Map<UUID, String> CHOSEN = new ConcurrentHashMap<>();

	public static void load() {
		Path path = path();
		if (!Files.exists(path)) return;

		Properties properties = new Properties();
		try (var in = Files.newInputStream(path)) {
			properties.load(in);
		} catch (IOException e) {
			Main.LOGGER.warn("Could not read {}", FILE, e);
			return;
		}
		for (String key : properties.stringPropertyNames()) {
			try {
				CHOSEN.put(UUID.fromString(key), properties.getProperty(key));
			} catch (IllegalArgumentException e) {
				Main.LOGGER.warn("Ignoring '{}' in {}: not a UUID", key, FILE);
			}
		}
	}

	/** The name whose skin this player chose, or null for their own; always null when choosing is off. */
	public static String of(UUID player) {
		return SkinConfig.playerChoice() ? CHOSEN.get(player) : null;
	}

	/** Everyone's choice, for dressing heads at startup; empty when choosing is off. */
	public static Map<UUID, String> all() {
		return SkinConfig.playerChoice() ? Map.copyOf(CHOSEN) : Map.of();
	}

	/** @param name null to go back to their own skin */
	public static synchronized void set(UUID player, String name) {
		if (name == null) CHOSEN.remove(player);
		else CHOSEN.put(player, name);

		Properties properties = new Properties();
		CHOSEN.forEach((id, chosen) -> properties.setProperty(id.toString(), chosen));
		try (var out = Files.newOutputStream(path())) {
			properties.store(out, "Offline Skins: the skin each player picked with /skin, by UUID");
		} catch (IOException e) {
			Main.LOGGER.warn("Could not write {}", FILE, e);
		}
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE);
	}
}
