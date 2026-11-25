package justfatlard.offline_skins;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finding a skin for a name: the folder first, then Mojang.
 *
 * <p>Both ends produce the same thing - the bytes of a PNG - which is what makes the two paths
 * interchangeable. The alternative would have been to hand the client a texture URL for the Mojang
 * case, and that is a different mechanism with different failure modes for no gain; the picture is
 * the picture wherever it came from.
 */
public final class SkinSource {
	private SkinSource() {}

	/** A skin and how its arms are drawn. */
	public record Skin(byte[] png, boolean slim) {}

	/** A skin with the name its file carries, in the file's own case. */
	public record Named(String name, Skin skin) {}

	/** What Mojang will take as a name, and so all that is ever put into a request URL. */
	private static final java.util.regex.Pattern ACCOUNT_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");

	public static boolean isAccountName(String name) {
		return name != null && ACCOUNT_NAME.matcher(name).matches();
	}

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private static final String NAME_TO_PROFILE =
		"https://api.mojang.com/users/profiles/minecraft/";
	private static final String PROFILE_TEXTURES =
		"https://sessionserver.mojang.com/session/minecraft/profile/";

	/**
	 * The skin file for this player, or null when there is none.
	 *
	 * <p>Matched without regard to case, because a player's name is not something an admin should
	 * have to reproduce exactly to dress them, and the file is being named by a human.
	 */
	public static Skin fromFile(String playerName) {
		return readFrom(SkinConfig.skinsDir(), playerName);
	}

	/**
	 * A previously fetched skin, kept on disk so a restart is not a reason to ask Mojang again.
	 *
	 * <p>Read after the admin's own folder and never before it: a cached face is what somebody once
	 * had, and a file an admin put there is what they want them to have now.
	 */
	public static Skin fromCache(String playerName) {
		if (SkinConfig.cache() != SkinConfig.Cache.FOREVER) return null;
		return readFrom(SkinConfig.cacheDir(), playerName);
	}

	/** Keep a fetched skin, so the next join costs nothing. */
	public static void cache(String playerName, Skin skin) {
		if (SkinConfig.cache() != SkinConfig.Cache.FOREVER) return;

		try {
			Path dir = SkinConfig.cacheDir();
			Files.createDirectories(dir);
			// Named as the player is, not lowercased: on an offline server the name's own case is
			// what their UUID is made from, and this file is read back as a name after a restart.
			Files.write(dir.resolve(playerName + (skin.slim() ? ".slim.png" : ".png")), skin.png());
		} catch (IOException e) {
			Main.LOGGER.warn("Could not cache the skin for {}", playerName, e);
		}
	}

	/**
	 * Every skin in a folder, by the name each file carries, in the file's own case.
	 *
	 * <p>For dressing people who are not here: a head left behind belongs to somebody, and the
	 * folder is the only record of their face until they come back.
	 */
	public static Map<String, Skin> allIn(Path dir) {
		Map<String, Skin> found = new LinkedHashMap<>();
		if (!Files.isDirectory(dir)) return found;

		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString();
				String lower = name.toLowerCase(Locale.ROOT);
				boolean slim = lower.endsWith(".slim.png");
				if (!slim && !lower.endsWith(".png")) continue;

				String player = name.substring(0, name.length() - (slim ? ".slim.png" : ".png").length());
				byte[] png = SkinImages.ready(Files.readAllBytes(file));
				if (png == null) Main.LOGGER.warn("Skipping {}: not a 64x64 or 64x32 skin", file);
				else found.put(player, new Skin(png, slim));
			}
		} catch (IOException e) {
			Main.LOGGER.warn("Could not read the skins in {}", dir, e);
		}
		return found;
	}

	/**
	 * Every face the folders hold, by lowercased name: the cache folder under {@code forever}, then
	 * the admin's folder over it, so a file put there wins over a cached fetch of the same person.
	 */
	public static Map<String, Named> library() {
		Map<String, Named> known = new LinkedHashMap<>();
		if (SkinConfig.cache() == SkinConfig.Cache.FOREVER) {
			allIn(SkinConfig.cacheDir()).forEach((name, skin) ->
				known.put(name.toLowerCase(Locale.ROOT), new Named(name, skin)));
		}
		allIn(SkinConfig.skinsDir()).forEach((name, skin) ->
			known.put(name.toLowerCase(Locale.ROOT), new Named(name, skin)));
		return known;
	}

	private static Skin readFrom(Path dir, String playerName) {
		if (!Files.isDirectory(dir)) return null;

		String wide = playerName.toLowerCase(Locale.ROOT) + ".png";
		String slim = playerName.toLowerCase(Locale.ROOT) + ".slim.png";

		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
				if (!name.equals(wide) && !name.equals(slim)) continue;
				byte[] png = SkinImages.ready(Files.readAllBytes(file));
				if (png == null) Main.LOGGER.warn("Ignoring {}: not a 64x64 or 64x32 skin", file);
				return png == null ? null : new Skin(png, name.equals(slim));
			}
		} catch (IOException e) {
			Main.LOGGER.warn("Could not read a skin file for {}", playerName, e);
		}
		return null;
	}

	/**
	 * The skin this name really owns, fetched from Mojang, or null if there is no such account.
	 *
	 * <p>Two calls: a name is not a profile, and only a profile carries textures. Run off the main
	 * thread by the caller - these are network round trips to somebody else's servers, and a join
	 * should not wait on them.
	 */
	public static Skin fromMojang(String playerName) {
		if (!isAccountName(playerName)) return null;
		try {
			JsonObject profile = getJson(NAME_TO_PROFILE + playerName);
			if (profile == null || !profile.has("id")) return null;

			JsonObject full = getJson(PROFILE_TEXTURES + profile.get("id").getAsString());
			if (full == null || !full.has("properties")) return null;

			for (var element : full.getAsJsonArray("properties")) {
				JsonObject property = element.getAsJsonObject();
				if (!"textures".equals(property.get("name").getAsString())) continue;

				JsonObject textures = JsonParser.parseString(new String(
					Base64.getDecoder().decode(property.get("value").getAsString())))
					.getAsJsonObject().getAsJsonObject("textures");
				if (textures == null || !textures.has("SKIN")) return null;

				JsonObject skin = textures.getAsJsonObject("SKIN");
				// The model is only stated when it is the slim one; its absence means the default.
				boolean slim = skin.has("metadata")
					&& "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());

				return new Skin(SkinImages.ready(getBytes(skin.get("url").getAsString())), slim);
			}
		} catch (Exception e) {
			Main.LOGGER.warn("Could not fetch a skin for {} from Mojang: {}",
				playerName, e.getMessage());
		}
		return null;
	}

	private static JsonObject getJson(String url) throws IOException, InterruptedException {
		HttpResponse<String> response = HTTP.send(
			HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).build(),
			HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200 || response.body().isBlank()) return null;
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private static byte[] getBytes(String url) throws IOException, InterruptedException {
		HttpResponse<byte[]> response = HTTP.send(
			HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).build(),
			HttpResponse.BodyHandlers.ofByteArray());
		return response.statusCode() == 200 ? response.body() : null;
	}
}
