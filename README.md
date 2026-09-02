# Offline Skins

A Fabric mod that gives offline-mode players a face: a PNG on the server named after them, or the
skin their name really owns, fetched from Mojang.

## What This Mod Does

An offline server mints its own UUIDs, so nobody's profile carries textures and everybody is Steve.
When a player joins, this looks for a skin for their name and dresses them in it.

**A file wins, always.** Drop a 64x64 skin PNG into the skins folder named after the player, as in
`Steve.png`, and that is what they wear. Names are matched without regard to case. For the
three-pixel-arm model, name the file `Steve.slim.png` instead. Files are re-read on every join and
never cached, so a skin you put in the folder takes effect the next time that player connects,
whatever the cache setting says.

**Otherwise, the real one.** With no file, the player is looked up by name at Mojang and the skin
that account actually owns is fetched and used, slim or wide as the account says. A name with no
account keeps the default skin.

**A join never waits.** The file read and both Mojang round trips happen on one background thread.
The player is in the world immediately and is dressed a moment later, when the answer comes back.
If they have already left by then, nothing happens.

**The picture travels, not a link.** A client resolves a profile's skin through authlib, which
checks the texture URL against a list of domains it fetches from Mojang, so a server cannot point
that mechanism at its own file no matter how it phrases the answer. This hands the client the PNG
itself through Pandorical, and it does so for the Mojang case too: one route, one set of failures.

## Configuration

`config/offline-skins.properties` is written with these defaults and its own explanation the first
time it is missing.

| Key | Default | What it does |
|-----|---------|--------------|
| `mojang_fallback` | `true` | When no file matches, look the player up by name and use the skin they own. Set to `false` to use files only. |
| `skins_directory` | `offline-skins` | Where the PNGs live, relative to the config directory. Created if absent. |
| `skin_cache` | `forever` | How long a skin fetched from Mojang is remembered. See below. |

`skin_cache` takes one of three values:

- **`forever`**: written to disk and never fetched twice, across restarts too. Costs a few KB per
  player, kept indefinitely.
- **`session`**: kept in memory only while the player is connected, and dropped when they leave.
  Costs one fetch per join.
- **`off`**: fetched every time. For testing.

An unrecognised value logs a warning and behaves as `forever`.

Cached skins are written beside the skins folder, not inside it, in a folder named
`<skins_directory>-cache`. A cached copy is not something anybody chose, and mixing the two would
fill the admin's folder with faces nobody put there.

## How A Skin Is Found

In this order, stopping at the first answer:

1. **The skins folder.** `<name>.png` or `<name>.slim.png`, matched without regard to case. Read
   every join.
2. **This session's memory**, when `skin_cache` is `session` and the player was fetched earlier
   in this session.
3. **The cache folder**, when `skin_cache` is `forever`.
4. **Mojang**, when `mojang_fallback` is on: the name is resolved to a profile, the profile's
   textures are read, and the skin is downloaded. The result is then cached according to
   `skin_cache`.

A file is deliberately ahead of every cache: a cached face is what somebody once had, and a file an
admin put there is what they should have now.

The log records each dressing and where the skin came from: `file`, `cache` or `mojang`.

## Pandorical

Offline Skins delivers the skin as an image through Pandorical's skin API, which is the only route
that reaches a client's renderer with a server-supplied picture.

**The Pandorical mod must be installed client-side** to see the skins. Without it the mod still runs
and still finds the skins, but a connecting client has no way to receive them and sees the default
skin, exactly as an offline server does without this mod.

## Installation

Install server-side alongside its declared dependencies (see `fabric.mod.json`); connecting clients
need only Pandorical. Version targets live in `gradle.properties` (Minecraft, loader, Fabric API)
and `fabric.mod.json` (Java).

## License

MIT, see [LICENSE](LICENSE).
