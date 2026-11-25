package justfatlard.offline_skins;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * A skin a player brought themselves, by a link to its PNG, kept as theirs alone.
 *
 * <p>Kept by UUID in a folder of its own, never in the skins folder: a file there named after
 * somebody dresses that somebody, and a player's upload must not dress anyone but them.
 */
public final class SkinUploads {
	private SkinUploads() {}

	/**
	 * The choice that means "my upload". A colon cannot be in an account name or, on Windows, a
	 * file name, so no skin in the folders can be mistaken for it.
	 */
	public static final String CHOICE = ":upload";

	/** Far above any real skin, which is a few KB; well below anything that hurts to hold. */
	private static final int MOST_BYTES = 256 * 1024;
	private static final int MOST_REDIRECTS = 3;

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	/** Why a link gave no skin, in words for the player. */
	public static final class Refused extends Exception {
		Refused(String words) {
			super(words, null, false, false);
		}
	}

	public static boolean isChoice(String target) {
		return CHOICE.equals(target);
	}

	public static boolean looksLikeLink(String text) {
		String lower = text.trim().toLowerCase(Locale.ROOT);
		return lower.startsWith("https://") || lower.startsWith("http://");
	}

	/** This player's upload, or null when they have none. */
	public static SkinSource.Skin of(UUID player) {
		for (boolean slim : new boolean[] {false, true}) {
			Path file = file(player, slim);
			if (!Files.isRegularFile(file)) continue;
			try {
				return new SkinSource.Skin(Files.readAllBytes(file), slim);
			} catch (IOException e) {
				Main.LOGGER.warn("Could not read the uploaded skin of {}", player, e);
			}
		}
		return null;
	}

	/** Keep it as this player's upload, over any they had. */
	public static void keep(UUID player, SkinSource.Skin skin) throws IOException {
		Files.createDirectories(SkinConfig.uploadsDir());
		Files.deleteIfExists(file(player, !skin.slim()));
		Files.write(file(player, skin.slim()), skin.png());
	}

	private static Path file(UUID player, boolean slim) {
		return SkinConfig.uploadsDir().resolve(player + (slim ? ".slim.png" : ".png"));
	}

	/**
	 * The skin at a link, drawn afresh, so what is kept is a plain 64x64 PNG and nothing else the
	 * file carried. Slim or wide is read off the picture. On the lookup thread.
	 */
	public static SkinSource.Skin fetch(String link) throws Refused {
		BufferedImage image = SkinImages.read(download(link));
		if (image == null) throw new Refused("That link is not a picture");
		if (!SkinImages.isSkinSize(image)) {
			throw new Refused("That picture is " + image.getWidth() + "x" + image.getHeight() + ", not a 64x64 skin");
		}
		// Read before readying it, which paints in the empty columns that tell a slim skin apart.
		boolean slim = image.getHeight() == 64 && SkinImages.isSlim(image);
		return new SkinSource.Skin(SkinImages.png(SkinImages.ready(image)), slim);
	}

	/**
	 * The bytes behind a link, following redirects by hand so every hop is checked. A player
	 * chooses the link and the server fetches it, so it must reach only the public internet,
	 * never the machine itself or the network it sits on.
	 */
	private static byte[] download(String link) throws Refused {
		URI uri;
		try {
			uri = URI.create(link.trim());
		} catch (IllegalArgumentException e) {
			throw new Refused("That is not a link");
		}

		for (int hop = 0; hop <= MOST_REDIRECTS; hop++) {
			checkPublic(uri);
			HttpResponse<InputStream> response;
			try {
				response = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
					.header("User-Agent", "offline-skins").build(), HttpResponse.BodyHandlers.ofInputStream());
			} catch (IOException e) {
				throw new Refused("That link could not be reached");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new Refused("That link could not be reached");
			}

			int status = response.statusCode();
			try (InputStream body = response.body()) {
				if (status >= 300 && status < 400) {
					String next = response.headers().firstValue("Location").orElse(null);
					if (next == null) throw new Refused("That link leads nowhere");
					try {
						uri = uri.resolve(next);
					} catch (IllegalArgumentException e) {
						throw new Refused("That link leads nowhere");
					}
					continue;
				}
				if (status != 200) throw new Refused("That link answered " + status);
				byte[] bytes = body.readNBytes(MOST_BYTES + 1);
				if (bytes.length > MOST_BYTES) throw new Refused("That file is too big for a skin");
				return bytes;
			} catch (IOException e) {
				throw new Refused("That link could not be read");
			}
		}
		throw new Refused("That link redirects too many times");
	}

	private static void checkPublic(URI uri) throws Refused {
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !scheme.equals("http")) throw new Refused("Only http and https links work");
		String host = uri.getHost();
		if (host == null) throw new Refused("That is not a link");
		try {
			for (InetAddress address : InetAddress.getAllByName(host)) {
				if (!isPublic(address)) throw new Refused("That link points inside the server's own network");
			}
		} catch (java.net.UnknownHostException e) {
			throw new Refused("No such site: " + host);
		}
	}

	private static boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
			|| address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
		byte[] raw = address.getAddress();
		if (address instanceof Inet6Address) return (raw[0] & 0xFE) != 0xFC;
		int first = raw[0] & 0xFF;
		int second = raw[1] & 0xFF;
		return first != 0 && !(first == 100 && second >= 64 && second < 128);
	}
}
