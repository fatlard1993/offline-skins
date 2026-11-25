package justfatlard.offline_skins.gametest;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import justfatlard.offline_skins.Main;
import justfatlard.offline_skins.SkinChoices;
import justfatlard.offline_skins.SkinConfig;
import justfatlard.pandorical.gametest.Smoke;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * A word typed into the picker finds skins online, and one found there is worn and kept.
 *
 * <p>Full runs only: it goes to MineSkin and to Mojang's texture server, and a smoke run should
 * not fail because somebody else's server is down.
 */
public final class SkinSearch implements FabricClientGameTest {
	/** A skin MineSkin lists under "knight", by its texture hash. */
	private static final String KNIGHT = "28b01a24308ef62d3dc3a57a8f4034fa5e4a0dfdc379832c0e26bf0950f4c7c5";

	@Override
	public void runTest(ClientGameTestContext context) {
		Smoke.run(context, "offline-skins-justfatlard", session -> {
			if (!session.full()) return;

			session.command("/skin");
			session.waitTicks(20);
			context.getInput().typeChars("knight");
			session.waitTicks(200);
			context.takeScreenshot("skin-search-knight");
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);
			session.waitTicks(5);

			String choice = "texture:" + KNIGHT + ":Knight";
			AtomicReference<Boolean> worn = new AtomicReference<>();
			session.onServer(server -> Main.switchTo(session.player(), choice, worn::set));
			for (int i = 0; i < 200 && worn.get() == null; i++) session.waitTicks(1);
			if (!Boolean.TRUE.equals(worn.get())) throw new AssertionError("wearing a skin found online failed: " + worn.get());

			AtomicReference<String> chosen = new AtomicReference<>();
			session.onServer(server -> chosen.set(SkinChoices.of(session.player().getUUID())));
			if (!choice.equals(chosen.get())) throw new AssertionError("the pick is " + chosen.get());
			if (!Files.isRegularFile(SkinConfig.onlineDir().resolve(KNIGHT + ".png"))
				&& !Files.isRegularFile(SkinConfig.onlineDir().resolve(KNIGHT + ".slim.png"))) {
				throw new AssertionError("the skin worn was not kept on disk");
			}

			session.command("/skin reset");
			session.waitTicks(20);
		});
	}
}
