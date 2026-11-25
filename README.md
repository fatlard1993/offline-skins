# Offline Skins

A Fabric mod that gives offline-mode players a face: a PNG on the server named after them, or the skin their name really owns, fetched from Mojang.

## What This Mod Does

An offline server mints its own UUIDs, so nobody's profile carries textures and everybody is Steve. When a player joins, this looks for a skin for their name and dresses them in it.

**A file wins, always.** Drop a 64x64 skin PNG into the skins folder named after the player, as in `Steve.png`, and that is what they wear. Names are matched without regard to case. For the three-pixel-arm model, name the file `Steve.slim.png` instead. Files are re-read on every join and never cached, so a skin you put in the folder takes effect the next time that player connects, whatever the cache setting says.

**Otherwise, the real one.** With no file, the player is looked up by name at Mojang and the skin that account actually owns is fetched and used, slim or wide as the account says. A name with no account keeps the default skin.

**Players can pick their own.** `/skin` opens a picker: every skin the server's folders hold, as faces, searched by typing part of a name and worn with a click. Type an account's name and, with Mojang lookups on, a button wears the skin that account owns. Paste a link to any skin PNG and a button wears that: it is kept as the player's upload, and stays in the picker as their own "Upload" tile after they try on others. "My own" goes back to the skin their name owns, whenever they like. The pick is kept across joins, and a player head wears it too, wherever its skin is kept on disk.

**A join never waits.** The file read and both Mojang round trips happen on one background thread. The player is in the world immediately and is dressed a moment later, when the answer comes back. If they have already left by then, nothing happens.

**The picture travels, not a link.** A client resolves a profile's skin through authlib, which checks the texture URL against a list of domains it fetches from Mojang, so a server cannot point that mechanism at its own file no matter how it phrases the answer. This hands the client the PNG itself through Pandorical, and it does so for the Mojang case too: one route, one set of failures.

## Configuration

`config/offline-skins.properties` is written with these defaults and its own explanation the first time it is missing. All but `skins_directory` are also on the Offline Skins page of the mod menu, for ops, as "Fetch skins from Mojang", "Keep fetched skins", "Players pick their skin" and "Players bring their own skin".

| Key | Default | What it does |
|-----|---------|--------------|
| `mojang_fallback` | `true` | When no file matches, look the player up by name and use the skin they own. Set to `false` to use files only. |
| `skins_directory` | `offline-skins` | Where the PNGs live, relative to the config directory. Created if absent. |
| `skin_cache` | `forever` | How long a skin fetched from Mojang is remembered. See below. |
| `player_choice` | `true` | Let players pick their skin with `/skin`. Set to `false` and everyone wears what the folders say; picks already made are kept, unused, for if it is turned back on. |
| `player_uploads` | `true` | Let players wear any skin PNG by a link, with `/skin link`. Needs `player_choice`. Set to `false` and the server fetches no player's links; uploads already kept stay on disk, unused. |

`skin_cache` takes one of three values:

- **`forever`**: written to disk and never fetched twice, across restarts too. Costs a few KB per player, kept indefinitely. Also the only setting under which a player's head keeps their face after they leave: every cached skin is handed out at startup, owner present or not.
- **`session`**: kept in memory only while the player is connected, and dropped when they leave. Costs one fetch per join.
- **`off`**: fetched every time. For testing.

An unrecognised value logs a warning and behaves as `forever`.

Cached skins are written beside the skins folder, not inside it, in a folder named `<skins_directory>-cache`. A cached copy is not something anybody chose, and mixing the two would fill the admin's folder with faces nobody put there.

## Picking A Skin

| Command | What it does |
|---------|--------------|
| `/skin` | Open the picker. Also on the server's action menu, as "Skin". |
| `/skin wear <name>` | Wear the skin of that name: a file in the folders, or that account's. Works without Pandorical on the client, for the players who have it to see. |
| `/skin link <url>` | Wear the skin PNG at that link. It is kept as your upload, replacing any earlier one. |
| `/skin upload` | Wear your upload again. |
| `/skin reset` | Go back to your own skin. Your upload is kept. |

The picker shows the skins folder and, under `skin_cache=forever`, the cache folder: everyone fetched so far, plus every account a player has looked up. A pick is kept only once a skin has been found for it, so a name with no skin anywhere changes nothing. Picks are stored by UUID in `config/offline-skins-choices.properties`.

## Bringing Your Own Skin

A link to any 64x64 skin PNG works: a skin site's download link, a Discord attachment, an image host. Old 64x32 skins work too, converted the way the game converts them. Slim or wide is read off the picture.

The server fetches the link, so it will only fetch from the public internet: a link into the server's own machine or network is refused, and so is every redirect that leads there. The picture is redrawn before it is kept, so what is stored is a plain PNG and nothing else the file carried. Files over 256 KB are refused.

Uploads are kept by UUID in `<skins_directory>-uploads`, beside the skins folder and never in it: a file there named after somebody would dress that somebody. A player's upload shows in their picker only, and dresses only them. Deleting a player's file there removes their upload.

**A pick wins over a file named after the player.** It is the player's own decision about their own face. An admin who wants the folder to decide turns `player_choice` off.

Each skin shown in the picker is handed to every client once, under a UUID of its own, so its face can be drawn on the tile. Like a skin kept for a player head, that is a few KB per skin sent to each player as they join, for the life of the server.

## How A Skin Is Found

The name looked up is the one the player picked, or their own when they have not picked one. In this order, stopping at the first answer:

1. **The skins folder.** `<name>.png` or `<name>.slim.png`, matched without regard to case. Read every join.
2. **This session's memory**, when `skin_cache` is `session` and the player was fetched earlier in this session.
3. **The cache folder**, when `skin_cache` is `forever`.
4. **Mojang**, when `mojang_fallback` is on: the name is resolved to a profile, the profile's textures are read, and the skin is downloaded. The result is then cached according to `skin_cache`.

A player wearing their upload skips all of this: their upload is read from its folder, and if it has gone, they wear the default skin.

Every skin, wherever it came from, is readied the way the game readies one it downloads itself: an old 64x32 skin gets the arm and leg the 64x64 layout needs, mirrored from the ones it has. A file that is neither size is skipped, with a warning in the log.

A file is deliberately ahead of every cache: a cached face is what somebody once had, and a file an admin put there is what they should have now.

The log records each dressing and where the skin came from: `file`, `cache` or `mojang`.

## Pandorical

Offline Skins delivers the skin as an image through Pandorical's skin API, which is the only route that reaches a client's renderer with a server-supplied picture. Pandorical is required on the server: the server will not load this mod without it.

**The Pandorical mod must be installed client-side** to see the skins. Without it the mod still runs and still finds the skins, but a connecting client has no way to receive them and sees the default skin, exactly as an offline server does without this mod.

## Development

Installing is in [DEVELOPMENT.md](DEVELOPMENT.md).

## License

MIT, see [LICENSE](LICENSE).
