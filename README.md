# VT Picker

A Fabric mod for Minecraft 26.3: pick [Vanilla Tweaks](https://vanillatweaks.net) resource packs in game.

Unofficial. Not made by or affiliated with the Vanilla Tweaks team; the packs, icons and descriptions come
from their site.

## Use

- **Options > Resource Packs > Vanilla Tweaks** (top left), or the mod's config button in Mod Menu.
- Categories on the left, packs as tiles like on the site. Hover a tile for its description and what it can't be
  combined with; picking a pack drops the ones it clashes with.
- **Apply** (or just **Done**) asks vanillatweaks.net to build the pack, saves it in `resourcepacks/`, puts it on
  top of your packs and reloads.
- Picks are saved right away (`config/vtpicker.json`) and kept across restarts.

## Always current

The pack list is fetched from vanillatweaks.net each time the screen opens (the same list the site's picker uses),
so new and changed tweaks show up without a mod update. Offline, the last list seen is used.

## Needs

- Minecraft 26.3, Fabric Loader 0.20.1+
- Internet access to vanillatweaks.net
- Optional: Mod Menu

## Build

JDK 25: `gradlew build` -> `build/libs/vtpicker-*.jar`. `gradlew runClient -PopenTweaks=true` opens the picker on
the title screen.

## License

MIT (this mod's code only).
