package justfatlard.offline_skins;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * A skin made ready to draw, the way the game readies one it downloads itself.
 *
 * <p>Pandorical draws the picture it is handed as it stands, so what the game would do to a skin
 * on the way in is done here instead: an old 64x32 skin gets the lower half the 64x64 layout
 * needs, mirrored from its one arm and one leg; the body layer is made opaque; and an old skin's
 * hat layer, painted solid by the editors of its day, is cleared.
 */
public final class SkinImages {
	private SkinImages() {}

	/** The picture in these bytes, or null when they are not one. */
	public static BufferedImage read(byte[] png) {
		if (png == null || png.length == 0) return null;
		try {
			return ImageIO.read(new ByteArrayInputStream(png));
		} catch (IOException e) {
			return null;
		}
	}

	public static boolean isSkinSize(BufferedImage image) {
		return image.getWidth() == 64 && (image.getHeight() == 64 || image.getHeight() == 32);
	}

	/** A 64x64 copy, readied to draw, of a 64x64 or 64x32 skin. */
	public static BufferedImage ready(BufferedImage skin) {
		boolean legacy = skin.getHeight() == 32;
		BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		out.createGraphics().drawImage(skin, 0, 0, null);
		// What sits under a see-through pixel is never meant to be seen, but the opaque layers
		// below would show it; black instead, so two files that look alike ready alike.
		for (int y = 0; y < out.getHeight(); y++) {
			for (int x = 0; x < 64; x++) if ((out.getRGB(x, y) >>> 24) == 0) out.setRGB(x, y, 0);
		}

		if (legacy) {
			// The game's own copies, rect by rect: source corner, offset, size.
			mirror(out, 4, 16, 16, 32, 4, 4);
			mirror(out, 8, 16, 16, 32, 4, 4);
			mirror(out, 0, 20, 24, 32, 4, 12);
			mirror(out, 4, 20, 16, 32, 4, 12);
			mirror(out, 8, 20, 8, 32, 4, 12);
			mirror(out, 12, 20, 16, 32, 4, 12);
			mirror(out, 44, 16, -8, 32, 4, 4);
			mirror(out, 48, 16, -8, 32, 4, 4);
			mirror(out, 40, 20, 0, 32, 4, 12);
			mirror(out, 44, 20, -8, 32, 4, 12);
			mirror(out, 48, 20, -16, 32, 4, 12);
			mirror(out, 52, 20, -8, 32, 4, 12);
		}
		opaque(out, 0, 0, 32, 16);
		if (legacy) clearIfSolid(out, 32, 0, 64, 32);
		opaque(out, 0, 16, 64, 32);
		opaque(out, 16, 48, 48, 64);
		return out;
	}

	public static byte[] png(BufferedImage image) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(image, "png", out);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		return out.toByteArray();
	}

	/** These bytes readied to draw, or null when they are not a skin. */
	public static byte[] ready(byte[] png) {
		BufferedImage image = read(png);
		return image == null || !isSkinSize(image) ? null : png(ready(image));
	}

	/**
	 * Whether a readied skin is the slim model: the outer column of each slim arm is left empty,
	 * as at x 54 to 55 of the right arm's front, which a wide skin always paints.
	 */
	public static boolean isSlim(BufferedImage skin) {
		for (int y = 20; y < 32; y++) {
			for (int x = 54; x < 56; x++) {
				if ((skin.getRGB(x, y) >>> 24) != 0) return false;
			}
		}
		return true;
	}

	/** The face as the tab list draws it, hat over head: 64 ARGB values, row by row. */
	public static int[] face(BufferedImage skin) {
		int[] face = new int[64];
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 8; x++) {
				int head = skin.getRGB(8 + x, 8 + y);
				int hat = skin.getRGB(40 + x, 8 + y);
				face[y * 8 + x] = (hat >>> 24) >= 128 ? hat | 0xFF000000 : head;
			}
		}
		return face;
	}

	/**
	 * What a readied skin looks like, as a hash: two files that draw the same are the same skin,
	 * however their bytes differ, so a re-upload that changed only the file's metadata, or the
	 * colour under a see-through pixel, is a duplicate.
	 */
	public static String key(BufferedImage skin) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			byte[] pixel = new byte[4];
			for (int y = 0; y < skin.getHeight(); y++) {
				for (int x = 0; x < skin.getWidth(); x++) {
					int argb = skin.getRGB(x, y);
					pixel[0] = (byte) (argb >>> 24);
					pixel[1] = (byte) (argb >>> 16);
					pixel[2] = (byte) (argb >>> 8);
					pixel[3] = (byte) argb;
					digest.update(pixel);
				}
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The rect at x, y copied dx, dy along, flipped left to right. */
	private static void mirror(BufferedImage image, int x, int y, int dx, int dy, int w, int h) {
		for (int row = 0; row < h; row++) {
			for (int col = 0; col < w; col++) {
				image.setRGB(x + dx + w - 1 - col, y + dy + row, image.getRGB(x + col, y + row));
			}
		}
	}

	private static void opaque(BufferedImage image, int x0, int y0, int x1, int y1) {
		for (int x = x0; x < x1; x++) {
			for (int y = y0; y < y1; y++) image.setRGB(x, y, image.getRGB(x, y) | 0xFF000000);
		}
	}

	/** Clear the rect when nothing in it is see-through: it was painted solid, not drawn on. */
	private static void clearIfSolid(BufferedImage image, int x0, int y0, int x1, int y1) {
		for (int x = x0; x < x1; x++) {
			for (int y = y0; y < y1; y++) if ((image.getRGB(x, y) >>> 24) < 128) return;
		}
		for (int x = x0; x < x1; x++) {
			for (int y = y0; y < y1; y++) image.setRGB(x, y, image.getRGB(x, y) & 0x00FFFFFF);
		}
	}
}
