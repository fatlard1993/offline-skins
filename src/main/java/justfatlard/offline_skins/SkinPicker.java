package justfatlard.offline_skins;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import justfatlard.pandorical.api.ActionMenuApi;
import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.ComponentUpdateBuilder;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenApi;
import justfatlard.pandorical.api.ScreenBuilder;
import justfatlard.pandorical.protocol.ComponentUpdate;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /skin}: every face the server's folders hold, as faces, found by typing part of a name and
 * worn with a click; and, where Mojang lookups are on, any account's skin by its name.
 *
 * <p>The grid is laid out once and refilled in place as the search changes, which leaves the search
 * box holding the keyboard between letters.
 *
 * <p>A face is drawn from a skin the client already holds, so each skin in the folders is handed
 * out once under a UUID of its own to be drawn by. Not the UUID of the name it is filed under: that
 * player may have picked another skin, and their tile would then show the wrong face.
 */
public final class SkinPicker {
	private SkinPicker() {}

	static final String TYPE = Main.MOD_ID + ":picker";

	private static final int PAD = 8;
	private static final int TOP = 20;
	private static final int BUTTON = 20;
	private static final int GAP = 4;
	private static final int COLUMNS = 5;
	private static final int ROWS = 3;
	private static final int PAGE = COLUMNS * ROWS;
	private static final int TILE_W = 40;
	private static final int TILE_H = 36;
	private static final int FACE = 20;
	private static final int INNER = COLUMNS * TILE_W + (COLUMNS - 1) * GAP;
	private static final int WIDTH = INNER + PAD * 2;
	private static final int GRID_Y = TOP + 24 + 22;
	private static final int PAGER_Y = GRID_Y + ROWS * (TILE_H + GAP);
	private static final int HEIGHT = PAGER_Y + BUTTON + PAD;
	private static final int OWN_W = 60;
	/** Room for a pasted link, which is far longer than any name. */
	private static final int MAX_QUERY = 512;
	/** The game's own colour for words on a panel. */
	private static final String INK = "#FF404040";

	/** A tile: the name shown, the face drawn, and what clicking it wears. */
	private record Entry(String name, UUID face, String target) {}

	private static final class Open {
		final String screenId;
		final List<Entry> library;
		String query;
		int page;

		Open(String screenId, List<Entry> library, String query, int page) {
			this.screenId = screenId;
			this.library = library;
			this.query = query;
			this.page = page;
		}
	}

	private static final Map<UUID, Open> OPEN = new ConcurrentHashMap<>();
	/** Players with a switch on its way, so a second click is not a second trip to Mojang. */
	private static final Set<UUID> BUSY = ConcurrentHashMap.newKeySet();
	/** Each face handed out to draw tiles by, as the hash of what was handed out. */
	private static final Map<UUID, Integer> FACES = new ConcurrentHashMap<>();

	public static void register() {
		ScreenApi screens = PandoricalApi.screens();
		screens.onActionFallback(TYPE, SkinPicker::pressed);
		screens.onClose(TYPE, player -> OPEN.remove(player.getUUID()));

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> commands(dispatcher));
		PandoricalApi.actionMenus().suggestButton(ActionMenuApi.Button.runs("minecraft:player_head", "Skin", "skin"));
		PandoricalApi.commandHelp().describe("/skin", "Pick the skin you wear, from the server's or any account's.");
		PandoricalApi.commandHelp().describe("/skin wear <name>", "Wear the skin of that name: a file on the server, or the account's.");
		PandoricalApi.commandHelp().describe("/skin link <url>", "Wear the skin PNG at that link, kept as your upload.");
		PandoricalApi.commandHelp().describe("/skin upload", "Wear your upload again.");
		PandoricalApi.commandHelp().describe("/skin reset", "Go back to your own skin.");
	}

	public static void forget(UUID player) {
		OPEN.remove(player);
		BUSY.remove(player);
	}

	private static void commands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("skin")
			.executes(context -> {
				open(context.getSource().getPlayerOrException(), "", 0);
				return 1;
			})
			.then(Commands.literal("wear")
				.then(Commands.argument("name", StringArgumentType.word())
					.executes(context -> {
						ServerPlayer player = context.getSource().getPlayerOrException();
						String name = StringArgumentType.getString(context, "name");
						if (allowed(player)) switchTo(player, name, ok -> say(player, ok
							? "You are wearing " + name + "'s skin" : "No skin found for " + name));
						return 1;
					})))
			.then(Commands.literal("link")
				.then(Commands.argument("url", StringArgumentType.greedyString())
					.executes(context -> {
						ServerPlayer player = context.getSource().getPlayerOrException();
						if (uploadsAllowed(player)) wearLink(player, StringArgumentType.getString(context, "url"),
							refused -> say(player, refused != null ? refused
								: "You are wearing your upload. /skin reset goes back to your own"));
						return 1;
					})))
			.then(Commands.literal("upload")
				.executes(context -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					if (uploadsAllowed(player)) switchTo(player, SkinUploads.CHOICE, ok -> say(player, ok
						? "You are wearing your upload" : "You have no upload yet. /skin link <url> brings one"));
					return 1;
				}))
			.then(Commands.literal("reset")
				.executes(context -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					if (allowed(player)) switchTo(player, null, ok -> say(player, "You are wearing your own skin"));
					return 1;
				})));
	}

	private static boolean allowed(ServerPlayer player) {
		if (SkinConfig.playerChoice()) return true;
		say(player, "Picking a skin is turned off on this server");
		return false;
	}

	private static boolean uploadsAllowed(ServerPlayer player) {
		if (!allowed(player)) return false;
		if (SkinConfig.playerUploads()) return true;
		say(player, "Bringing your own skin is turned off on this server");
		return false;
	}

	/**
	 * Fetch the skin at a link, keep it as this player's upload, and wear it.
	 *
	 * @param done told on the server thread: null once it is worn, or why not, in words for the player
	 */
	private static void wearLink(ServerPlayer player, String link, java.util.function.Consumer<String> done) {
		UUID id = player.getUUID();
		if (!BUSY.add(id)) return;
		MinecraftServer server = player.level().getServer();
		Main.LOOKUP.execute(() -> {
			String refused = null;
			try {
				SkinUploads.keep(id, SkinUploads.fetch(link));
			} catch (SkinUploads.Refused e) {
				refused = e.getMessage();
			} catch (java.io.IOException e) {
				Main.LOGGER.warn("Could not keep the uploaded skin of {}", id, e);
				refused = "The server could not keep that skin";
			}
			String why = refused;
			server.execute(() -> {
				BUSY.remove(id);
				if (why != null) {
					done.accept(why);
					return;
				}
				switchTo(player, SkinUploads.CHOICE, ok -> done.accept(ok ? null : "That skin could not be worn"));
			});
		});
	}

	private static void say(ServerPlayer player, String words) {
		player.sendSystemMessage(Component.literal(words).withStyle(ChatFormatting.GRAY));
	}

	/** One switch at a time per player; a click while one is on its way does nothing. */
	private static void switchTo(ServerPlayer player, String name, java.util.function.Consumer<Boolean> done) {
		if (!BUSY.add(player.getUUID())) return;
		Main.switchTo(player, name, ok -> {
			BUSY.remove(player.getUUID());
			done.accept(ok);
		});
	}

	// --- The screen ---

	/** Reads the folders off the server thread, then shows them. */
	private static void open(ServerPlayer player, String query, int page) {
		if (!allowed(player)) return;
		if (!PandoricalApi.isAvailable(player)) {
			say(player, "Skins need Pandorical on your client to be seen. /skin wear <name> picks one anyway");
			return;
		}
		MinecraftServer server = player.level().getServer();
		UUID id = player.getUUID();
		Main.LOOKUP.execute(() -> {
			Map<String, SkinSource.Named> library = SkinSource.library();
			SkinSource.Skin upload = SkinConfig.playerUploads() ? SkinUploads.of(id) : null;
			server.execute(() -> {
				if (player.hasDisconnected()) return;
				show(player, handOut(server, library, id, upload), query, page);
			});
		});
	}

	/**
	 * Every skin as a tile, its face handed out to draw by where that has not been done already;
	 * the player's own upload first, seen by them alone.
	 */
	private static List<Entry> handOut(MinecraftServer server, Map<String, SkinSource.Named> library,
			UUID player, SkinSource.Skin upload) {
		List<Entry> entries = new ArrayList<>();
		for (var known : library.entrySet()) {
			UUID face = handOut(server, "offline-skins-face:" + known.getKey(), known.getValue().skin());
			entries.add(new Entry(known.getValue().name(), face, known.getValue().name()));
		}
		entries.sort(java.util.Comparator.comparing(entry -> entry.name().toLowerCase(Locale.ROOT)));
		if (upload != null) {
			entries.addFirst(new Entry("Upload", handOut(server, "offline-skins-upload:" + player, upload), SkinUploads.CHOICE));
		}
		return List.copyOf(entries);
	}

	private static UUID handOut(MinecraftServer server, String key, SkinSource.Skin skin) {
		UUID face = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
		int hash = Arrays.hashCode(skin.png()) * 31 + (skin.slim() ? 1 : 0);
		if (!Integer.valueOf(hash).equals(FACES.put(face, hash))) {
			PandoricalApi.skins().set(server, face, skin.png(), skin.slim());
		}
		return face;
	}

	private static void show(ServerPlayer player, List<Entry> library, String query, int page) {
		ScreenBuilder screen = new ScreenBuilder(TYPE).title("Skin").pauseGame(false).size(WIDTH, HEIGHT);
		Open open = new Open(screen.screenId(), library, query, page);
		clampPage(open);
		Map<String, Map<String, String>> state = state(player, open);

		screen.panel("dialog", 0, 0, WIDTH, HEIGHT, Map.of(ComponentType.PROP_BORDER, "beveled"));
		screen.component(text("title", PAD, 7, "Skin"));

		screen.component(new ComponentBuilder("own_face", ComponentType.PLAYER_FACE)
			.bounds(PAD, TOP + 2, 16, 16)
			.prop(ComponentType.PROP_PLAYER, player.getUUID().toString()));
		screen.component(text("current", PAD + 20, TOP + 6, "").props(state.get("current")));
		screen.component(button("own", WIDTH - PAD - OWN_W, TOP, OWN_W, BUTTON)
			.prop(ComponentType.PROP_LABEL, "My own")
			.prop(ComponentType.PROP_TOOLTIP, "Go back to your own skin")
			.props(state.get("own")));

		screen.component(new ComponentBuilder("search", ComponentType.TEXT_INPUT)
			.bounds(PAD, TOP + 24, INNER, 16)
			.prop(ComponentType.PROP_VALUE, query)
			.prop(ComponentType.PROP_PLACEHOLDER, placeholder())
			.prop(ComponentType.PROP_MAX_LENGTH, String.valueOf(MAX_QUERY))
			.prop(ComponentType.PROP_FOCUSED, "true"));

		for (int i = 0; i < PAGE; i++) {
			int x = PAD + (i % COLUMNS) * (TILE_W + GAP);
			int y = GRID_Y + (i / COLUMNS) * (TILE_H + GAP);
			screen.component(button("tile:" + i, x, y, TILE_W, TILE_H).props(state.get("tile:" + i)));
			screen.component(new ComponentBuilder("face:" + i, ComponentType.PLAYER_FACE)
				.bounds(x + (TILE_W - FACE) / 2, y + 3, FACE, FACE)
				.props(state.get("face:" + i)));
			screen.component(new ComponentBuilder("name:" + i, ComponentType.TEXT)
				.bounds(x, y + TILE_H - 11, TILE_W, 10)
				.prop(ComponentType.PROP_ALIGN, "center")
				.prop(ComponentType.PROP_SHADOW, "true")
				.props(state.get("name:" + i)));
		}

		screen.component(button("prev", PAD, PAGER_Y, BUTTON, BUTTON).prop(ComponentType.PROP_LABEL, "<").props(state.get("prev")));
		screen.component(new ComponentBuilder("page", ComponentType.TEXT)
			.bounds(PAD + BUTTON, PAGER_Y + 6, 56, 10)
			.prop(ComponentType.PROP_ALIGN, "center")
			.prop(ComponentType.PROP_COLOR, INK)
			.props(state.get("page")));
		screen.component(button("next", PAD + BUTTON + 56, PAGER_Y, BUTTON, BUTTON).prop(ComponentType.PROP_LABEL, ">").props(state.get("next")));
		int lookupX = PAD + BUTTON * 2 + 56 + GAP;
		screen.component(button("lookup", lookupX, PAGER_Y, WIDTH - PAD - lookupX, BUTTON).props(state.get("lookup")));
		// A focused box hides its placeholder, so what it is for is said beside it instead.
		screen.component(new ComponentBuilder("hint", ComponentType.TEXT)
			.bounds(lookupX, PAGER_Y + 6, WIDTH - PAD - lookupX, 10)
			.prop(ComponentType.PROP_ALIGN, "right")
			.prop(ComponentType.PROP_COLOR, "#FF707070")
			.prop(ComponentType.PROP_TEXT, SkinConfig.playerUploads() ? "or paste a skin link"
				: SkinConfig.mojangFallback() ? "type any account name" : "type to search")
			.props(state.get("hint")));

		PandoricalApi.screens().open(player, screen.build());
		OPEN.put(player.getUUID(), open);
	}

	private static String placeholder() {
		if (SkinConfig.playerUploads()) return SkinConfig.mojangFallback() ? "Search, a name, or a skin link" : "Search, or a skin link";
		return SkinConfig.mojangFallback() ? "Search, or type an account's name" : "Search the server's skins";
	}

	/** Each part that changes with the search, the page or the skin worn, by component. */
	private static Map<String, Map<String, String>> state(ServerPlayer player, Open open) {
		List<Entry> found = matches(open);
		int pages = pages(found);
		String chosen = SkinChoices.of(player.getUUID());
		Map<String, Map<String, String>> state = new LinkedHashMap<>();

		state.put("current", Map.of(ComponentType.PROP_TEXT, clip(chosen == null ? "Wearing your own skin"
			: SkinUploads.isChoice(chosen) ? "Wearing your upload" : "Wearing " + chosen + "'s skin", 22)));
		state.put("own", Map.of(ComponentType.PROP_ENABLED, String.valueOf(chosen != null)));

		for (int i = 0; i < PAGE; i++) {
			int index = open.page * PAGE + i;
			Entry entry = index < found.size() ? found.get(index) : null;
			String shown = String.valueOf(entry != null);
			boolean worn = entry != null && entry.target().equalsIgnoreCase(chosen);
			state.put("tile:" + i, Map.of(ComponentType.PROP_VISIBLE, shown,
				ComponentType.PROP_TOOLTIP, entry == null ? "" : worn ? "Wearing " + entry.name() : "Wear " + entry.name(),
				ComponentType.PROP_STYLE, worn ? "pressed" : "default"));
			state.put("face:" + i, Map.of(ComponentType.PROP_VISIBLE, shown,
				ComponentType.PROP_PLAYER, entry == null ? "" : entry.face().toString()));
			state.put("name:" + i, Map.of(ComponentType.PROP_VISIBLE, shown,
				ComponentType.PROP_TEXT, entry == null ? "" : clip(entry.name(), 7)));
		}

		boolean link = SkinConfig.playerUploads() && SkinUploads.looksLikeLink(open.query);
		state.put("page", Map.of(ComponentType.PROP_TEXT, found.isEmpty()
			? (link ? "" : open.library.isEmpty() ? "No skins yet" : "Nothing found") : (open.page + 1) + " of " + pages));
		state.put("prev", Map.of(ComponentType.PROP_ENABLED, String.valueOf(open.page > 0)));
		state.put("next", Map.of(ComponentType.PROP_ENABLED, String.valueOf(open.page + 1 < pages)));

		String name = open.query.trim();
		boolean lookup = SkinConfig.mojangFallback() && SkinSource.isAccountName(name)
			&& open.library.stream().noneMatch(entry -> entry.name().equalsIgnoreCase(name));
		state.put("lookup", Map.of(ComponentType.PROP_VISIBLE, String.valueOf(lookup || link),
			ComponentType.PROP_LABEL, link ? "Wear this link" : lookup ? clip("Wear " + name, 17) : "",
			ComponentType.PROP_TOOLTIP, link ? "Wear the skin at this link, kept as your upload"
				: lookup ? "Wear the skin the account " + name + " owns" : ""));
		state.put("hint", Map.of(ComponentType.PROP_VISIBLE, String.valueOf(!lookup && !link && open.query.isBlank())));
		return state;
	}

	/** The skins whose name holds what was typed, those starting with it ahead. */
	private static List<Entry> matches(Open open) {
		String query = open.query.trim().toLowerCase(Locale.ROOT);
		if (query.isEmpty()) return open.library;
		List<Entry> ahead = new ArrayList<>();
		List<Entry> rest = new ArrayList<>();
		for (Entry entry : open.library) {
			String name = entry.name().toLowerCase(Locale.ROOT);
			if (name.startsWith(query)) ahead.add(entry);
			else if (name.contains(query)) rest.add(entry);
		}
		ahead.addAll(rest);
		return ahead;
	}

	private static int pages(List<Entry> found) {
		return Math.max(1, (found.size() + PAGE - 1) / PAGE);
	}

	private static void clampPage(Open open) {
		open.page = Math.clamp(open.page, 0, pages(matches(open)) - 1);
	}

	/** The screen this player has open, or null if it has gone. */
	private static Open openFor(ServerPlayer player) {
		Open open = OPEN.get(player.getUUID());
		if (open == null || !open.screenId.equals(PandoricalApi.getOpenScreenId(player.getUUID()))) {
			OPEN.remove(player.getUUID());
			return null;
		}
		return open;
	}

	private static void refresh(ServerPlayer player, Open open) {
		List<ComponentUpdate> updates = new ArrayList<>();
		state(player, open).forEach((id, props) -> updates.add(new ComponentUpdateBuilder(id).props(props).build()));
		PandoricalApi.screens().update(player, open.screenId, updates);
	}

	private static void status(ServerPlayer player, Open open, String words) {
		PandoricalApi.screens().update(player, open.screenId,
			List.of(new ComponentUpdateBuilder("current").prop(ComponentType.PROP_TEXT, clip(words, 22)).build()));
	}

	private static void pressed(ServerPlayer player, Map<String, String> data) {
		String id = data.get(ScreenApi.FALLBACK_COMPONENT_ID_KEY);
		Open open = openFor(player);
		if (id == null || open == null) return;
		if (!SkinConfig.playerChoice()) {
			PandoricalApi.screens().close(player, open.screenId);
			allowed(player);
			return;
		}

		switch (id) {
			case "search" -> {
				String text = data.getOrDefault("text", "");
				open.query = text.length() > MAX_QUERY ? text.substring(0, MAX_QUERY) : text;
				open.page = 0;
				refresh(player, open);
			}
			case "prev", "next" -> {
				open.page += id.equals("prev") ? -1 : 1;
				clampPage(open);
				refresh(player, open);
			}
			case "own" -> wear(player, open, null);
			case "lookup" -> {
				String name = open.query.trim();
				if (SkinConfig.playerUploads() && SkinUploads.looksLikeLink(name)) wearLink(player, open, name);
				else if (SkinSource.isAccountName(name)) wear(player, open, name);
			}
			default -> {
				if (!id.startsWith("tile:")) return;
				int index;
				try {
					index = open.page * PAGE + Integer.parseInt(id.substring("tile:".length()));
				} catch (NumberFormatException e) {
					return;
				}
				List<Entry> found = matches(open);
				if (index >= 0 && index < found.size()) wear(player, open, found.get(index).target());
			}
		}
	}

	/**
	 * Switch, and show the screen again once it has: a lookup can put a new skin in the folders,
	 * and its tile belongs in the grid.
	 */
	private static void wear(ServerPlayer player, Open open, String name) {
		if (BUSY.contains(player.getUUID())) return;
		status(player, open, name == null ? "Changing back..."
			: SkinUploads.isChoice(name) ? "Changing..." : "Finding " + name + "...");
		switchTo(player, name, ok -> {
			Open still = openFor(player);
			if (still == null) return;
			if (ok) open(player, still.query, still.page);
			else status(player, still, "No skin found for " + name);
		});
	}

	/** Fetch the link, then show the screen again with the upload's tile in it and the search cleared. */
	private static void wearLink(ServerPlayer player, Open open, String link) {
		if (BUSY.contains(player.getUUID())) return;
		status(player, open, "Fetching the skin...");
		wearLink(player, link, refused -> {
			Open still = openFor(player);
			// The reason is often longer than the status line holds, so the whole of it goes to chat.
			if (refused != null) say(player, refused);
			if (still == null) return;
			if (refused == null) open(player, "", 0);
			else status(player, still, "That link didn't work");
		});
	}

	private static ComponentBuilder button(String id, int x, int y, int w, int h) {
		return new ComponentBuilder(id, ComponentType.BUTTON).bounds(x, y, w, h);
	}

	/** Words on the dialog, in the game's own dark grey. */
	private static ComponentBuilder text(String id, int x, int y, String words) {
		return new ComponentBuilder(id, ComponentType.TEXT).pos(x, y)
			.prop(ComponentType.PROP_TEXT, words)
			.prop(ComponentType.PROP_COLOR, INK);
	}

	/** At most {@code letters} of it, with an ellipsis where it was cut. */
	private static String clip(String words, int letters) {
		return words.length() <= letters ? words : words.substring(0, Math.max(1, letters - 1)) + "…";
	}
}
