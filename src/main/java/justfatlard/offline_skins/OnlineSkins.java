package justfatlard.offline_skins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Skins found by a word, from MineSkin's public gallery, and worn from Mojang's texture server.
 *
 * <p>MineSkin is a service that signs skins for plugins, so most of what it holds is unnamed or
 * named by a plugin; only skins a person named are shown, and only those whose name holds the
 * word. Its own filter puts its matches first and pads the page with anything, which is why the
 * names are checked again here.
 *
 * <p>Every skin there is served from {@code textures.minecraft.net} under its texture hash, which
 * is what a pick of one keeps.
 */
public final class OnlineSkins {
	private OnlineSkins() {}

	/** A pick of a skin found online: {@code texture:<hash>:<name>}. A colon is in no account or file name. */
	private static final String PREFIX = "texture:";

	/** Mojang's texture hashes are hex, and drop leading zeros. */
	private static final Pattern TEXTURE = Pattern.compile("[0-9a-f]{32,64}");

	private static final String LIST = "https://api.mineskin.org/v2/skins?size=48&filter=";
	private static final String TEXTURES = "https://textures.minecraft.net/texture/";

	/**
	 * Gallery pages read for one word, at most. Its filter's matches come first, so reading stops
	 * at the first page that holds none: past them is padding.
	 */
	private static final int PAGES = 3;
	public static final int MOST_RESULTS = 45;
	public static final int LEAST_QUERY = 3;
	private static final int MOST_NAME = 24;

	/** A found skin: its name, its texture hash, the skin itself, what it looks like, and its face. */
	public record Result(String name, String texture, SkinSource.Skin skin, String key, int[] face) {}

	/** One search at a time, off the lookup thread so a join is never kept waiting behind one. */
	static final ExecutorService SEARCH = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "offline-skins-search");
		thread.setDaemon(true);
		return thread;
	});

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	/** Recent searches, so a backspace and retype asks nobody twice. */
	private static final Map<String, Searched> SEARCHES = Collections.synchronizedMap(lru(64));
	/** Recent skins by texture hash; a skin under one hash never changes. */
	private static final Map<String, Result> SKINS = Collections.synchronizedMap(lru(512));
	private static final long KEEP_SEARCH_MS = 10 * 60 * 1000;

	private record Searched(List<Result> results, long at) {}

	public static boolean isChoice(String choice) {
		return choice != null && choice.startsWith(PREFIX);
	}

	public static String choice(Result result) {
		return PREFIX + result.texture() + ":" + result.name();
	}

	/** The name a pick of a found skin was shown under. */
	public static String nameOf(String choice) {
		int colon = choice.indexOf(':', PREFIX.length());
		return colon < 0 ? "a skin" : choice.substring(colon + 1);
	}

	private static String textureOf(String choice) {
		int colon = choice.indexOf(':', PREFIX.length());
		String texture = colon < 0 ? choice.substring(PREFIX.length()) : choice.substring(PREFIX.length(), colon);
		return TEXTURE.matcher(texture).matches() ? texture : null;
	}

	/**
	 * The skins whose name holds this word, one of each look, none the server already has under
	 * {@code known}. On the search thread.
	 */
	public static List<Result> search(String query, Set<String> known) {
		String word = query.trim().toLowerCase(Locale.ROOT);
		if (word.length() < LEAST_QUERY) return List.of();

		Searched searched = SEARCHES.get(word);
		if (searched == null || System.currentTimeMillis() - searched.at() > KEEP_SEARCH_MS) {
			searched = new Searched(fetch(word), System.currentTimeMillis());
			SEARCHES.put(word, searched);
		}
		List<Result> fresh = new ArrayList<>();
		for (Result result : searched.results()) if (!known.contains(result.key())) fresh.add(result);
		return fresh;
	}

	private static List<Result> fetch(String word) {
		Map<String, String> named = new LinkedHashMap<>();
		String after = null;
		try {
			for (int page = 0; page < PAGES && named.size() < MOST_RESULTS; page++) {
				String url = LIST + URLEncoder.encode(word, StandardCharsets.UTF_8)
					+ (after == null ? "" : "&after=" + URLEncoder.encode(after, StandardCharsets.UTF_8));
				HttpResponse<String> response = HTTP.send(request(url), HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() != 200) {
					Main.LOGGER.warn("MineSkin answered {} for '{}'", response.statusCode(), word);
					break;
				}
				JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
				int before = named.size();
				for (JsonElement element : body.getAsJsonArray("skins")) {
					JsonObject skin = element.getAsJsonObject();
					String name = string(skin, "name");
					String texture = string(skin, "texture");
					if (name == null || texture == null || !TEXTURE.matcher(texture).matches()) continue;
					name = name.strip();
					if (!name.toLowerCase(Locale.ROOT).contains(word) || name.length() > MOST_NAME) continue;
					named.putIfAbsent(texture, name);
				}
				JsonObject next = body.has("pagination") ? body.getAsJsonObject("pagination").getAsJsonObject("next") : null;
				after = next == null ? null : string(next, "after");
				if (after == null || named.size() == before) break;
			}
		} catch (Exception e) {
			Main.LOGGER.warn("Could not search MineSkin for '{}': {}", word, e.getMessage());
		}

		// Downloaded side by side: one at a time, a full page would take seconds.
		List<CompletableFuture<Result>> pending = new ArrayList<>();
		named.forEach((texture, name) -> pending.add(CompletableFuture.supplyAsync(() -> result(texture, name), SEARCH_DOWNLOADS)));
		List<Result> results = new ArrayList<>();
		Set<String> looks = new HashSet<>();
		for (CompletableFuture<Result> future : pending) {
			Result result = future.join();
			if (result != null && looks.add(result.key()) && results.size() < MOST_RESULTS) results.add(result);
		}
		return List.copyOf(results);
	}

	private static final ExecutorService SEARCH_DOWNLOADS = Executors.newFixedThreadPool(6, runnable -> {
		Thread thread = new Thread(runnable, "offline-skins-download");
		thread.setDaemon(true);
		return thread;
	});

	/** The skin under this texture hash, readied, or null when it cannot be had. */
	private static Result result(String texture, String name) {
		Result kept = SKINS.get(texture);
		if (kept != null) return new Result(name, texture, kept.skin(), kept.key(), kept.face());
		try {
			HttpResponse<byte[]> response = HTTP.send(request(TEXTURES + texture), HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) return null;
			BufferedImage image = SkinImages.read(response.body());
			if (image == null || !SkinImages.isSkinSize(image)) return null;
			boolean slim = image.getHeight() == 64 && SkinImages.isSlim(image);
			BufferedImage ready = SkinImages.ready(image);
			Result result = new Result(name, texture, new SkinSource.Skin(SkinImages.png(ready), slim),
				SkinImages.key(ready), SkinImages.face(ready));
			SKINS.put(texture, result);
			return result;
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/**
	 * The skin a pick of a found skin wears: kept on disk once worn, so it is there after a
	 * restart and for heads, and fetched by its hash the first time. On the lookup thread.
	 */
	public static SkinSource.Skin skinFor(String choice) {
		String texture = textureOf(choice);
		if (texture == null) return null;
		SkinSource.Skin kept = keptFor(choice);
		if (kept != null) return kept;
		Result result = result(texture, nameOf(choice));
		if (result == null) return null;
		try {
			Files.createDirectories(SkinConfig.onlineDir());
			Files.write(file(texture, result.skin().slim()), result.skin().png());
		} catch (IOException e) {
			Main.LOGGER.warn("Could not keep the skin {}", texture, e);
		}
		return result.skin();
	}

	/** The skin on disk for a pick, without going online; for dressing heads at startup. */
	public static SkinSource.Skin keptFor(String choice) {
		String texture = textureOf(choice);
		if (texture == null) return null;
		for (boolean slim : new boolean[] {false, true}) {
			Path file = file(texture, slim);
			try {
				if (Files.isRegularFile(file)) return new SkinSource.Skin(Files.readAllBytes(file), slim);
			} catch (IOException e) {
				Main.LOGGER.warn("Could not read {}", file, e);
			}
		}
		return null;
	}

	private static Path file(String texture, boolean slim) {
		return SkinConfig.onlineDir().resolve(texture + (slim ? ".slim.png" : ".png"));
	}

	private static HttpRequest request(String url) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
			.header("User-Agent", "offline-skins").build();
	}

	private static String string(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
	}

	private static <K, V> LinkedHashMap<K, V> lru(int most) {
		return new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > most;
			}
		};
	}
}
