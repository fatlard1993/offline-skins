package justfatlard.offline_skins.gametest;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import justfatlard.offline_skins.SkinChoices;
import justfatlard.offline_skins.SkinConfig;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.gametest.Smoke;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * A player opens the picker, wears a skin from the folder by name, and goes back to their own.
 *
 * <p>A file rather than an account, so the test never waits on Mojang.
 */
public final class SkinPick implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		Smoke.run(context, "offline-skins-justfatlard", session -> {
			try {
				Path file = SkinConfig.skinsDir().resolve("Tester.png");
				Files.createDirectories(file.getParent());
				ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", file.toFile());
			} catch (java.io.IOException e) {
				throw new AssertionError("could not write the test skin", e);
			}

			session.command("/skin");
			session.waitTicks(20);
			AtomicReference<String> screen = new AtomicReference<>();
			session.onServer(server -> screen.set(PandoricalApi.getOpenScreenId(session.player().getUUID())));
			if (screen.get() == null) throw new AssertionError("/skin opened no screen");
			if (session.full()) context.takeScreenshot("skin-picker");
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);
			session.waitTicks(5);

			session.command("/skin wear Tester");
			session.waitTicks(20);
			String worn = choice(session);
			if (!"Tester".equals(worn)) throw new AssertionError("wearing Tester left the choice at " + worn);

			session.command("/skin wear no_such_file_here");
			session.waitTicks(20);
			worn = choice(session);
			if (!"Tester".equals(worn)) throw new AssertionError("a name with no skin replaced the choice with " + worn);

			session.command("/skin reset");
			session.waitTicks(20);
			worn = choice(session);
			if (worn != null) throw new AssertionError("reset left the choice at " + worn);
		});
	}

	/** The player's pick, read on the server thread, which is the only one that may ask for the player. */
	private static String choice(Smoke.Session session) {
		AtomicReference<String> chosen = new AtomicReference<>();
		session.onServer(server -> chosen.set(SkinChoices.of(session.player().getUUID())));
		return chosen.get();
	}
}
