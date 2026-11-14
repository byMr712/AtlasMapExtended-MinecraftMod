> **Language:** [Русский](README.md) · English

<div align="center">

  <img width="150" alt="Atlas Map (Extended) logo" src="images/icon.png">
  <h3>Atlas Map (Extended)</h3>

</div>

<div align="center">

![Java 21+](https://img.shields.io/badge/Java-21+-blue.svg)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.10-blue.svg)
![Fabric](https://img.shields.io/badge/Loader-Fabric-blue.svg)
![NeoForge](https://img.shields.io/badge/Loader-NeoForge-blue.svg)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

</div>

A client-side mod full-screen world map and minimap, markers with icons, descriptions and screenshots, a ruler, map drawing, teleport to markers and **8 UI designs**.

> This mod was created and ported with the help of the AI based on the ideas of the author and viewers.

<div align="center">

  [<img width="900" alt="Download on CurseForge" src="images/curseforge_banner_en.png">](https://www.curseforge.com/minecraft/mc-mods/atlas-map-extended)

</div>

## Gallery
| World Map | Minimap |
| :---: | :---: |
| ![World Map](article_screenshots/webp/04_karta_spisok_i_metka.webp) | ![Minimap](article_screenshots/webp/02_minikarta.webp) |

| Settings | Themes |
| :---: | :---: |
| ![Settings](article_screenshots/webp/15_nastrojki.webp) | ![All 8 Themes](article_screenshots/webp/00_vse_8_dizajnov.webp) |

## Features

- **World Map** (key `M`): draws itself as you travel the world. Zoom, pan, and dimension switching are supported.
- **Minimap** (`N`, `=`/`-`): choose size, screen angle, scale, and coordinate display.
- **Markers**: place with right-click on the map or key `B` (marker at player position). A marker has a name, coordinates, description, and screenshots.
- **Marker List** (`J`): search, sort, hide markers.
- **183 icons** with search. Any icon can be **recoloured**: right-click an icon opens a palette of 60 colours.
- **Screenshots in markers**: add from the screenshots folder, via file picker, or by drag-and-drop. You can draw over a screenshot.
- **Ruler** (`R`): measures distance across multiple points.
- **Drawing on the map** (`G`): pen, line, rectangle, circle, and eraser. 60 colours and undo are available.
- **Teleport to marker**: enabled in settings. Works if commands are allowed (cheats in singleplayer or operator permissions on a server).
- **Settings** (gear button on the map and in the main menu). Choose from 8 designs: Papyrus, Stone, Deepslate, Light, Dark, Oak, Nether, End.
- **Map Atlas** in the main menu and **on-the-fly map switcher** (folder button on the map): quickly switch between saved maps, create separate maps for servers, and safely archive maps on world deletion or seed change.
- Interface in Russian and English.

## Installation

The mod runs on **Fabric** and **NeoForge**. Fabric API and other libraries are not required.

1. Install [Fabric Loader](https://fabricmc.net/use/) (0.17.0 or newer) or [NeoForge](https://neoforged.net/) for Minecraft **1.21.10**.
2. Place one JAR for your loader into the `mods` folder:
   - `AtlasMapExtended-Fabric-1.21.10-v1.1.1.jar` for Fabric;
   - `AtlasMapExtended-NeoForge-1.21.10-v1.1.1.jar` for NeoForge.
3. Launch the game.

The mod is client-side only; no need to install it on a server.

## Controls

| Key | Action |
|---|---|
| `M` | Open or close the map |
| `J` | Marker list |
| `B` | Marker at player position |
| `N` | Toggle minimap |
| `=` / `-` | Minimap scale |
| `R` | Ruler (on the map) |
| `G` | Drawing (on the map) |
| `H` | Show or hide drawings |
| Right-click on icon | Recolour marker icon |

All keys can be remapped in the standard Minecraft controls settings.

## Map and Data Storage

All mod data is stored independently in `.minecraft/atlasmapextended/`:
- **Singleplayer worlds**: `.minecraft/atlasmapextended/worlds/local_<world_folder>/`
- **Multiplayer servers**: `.minecraft/atlasmapextended/worlds/server_<address_hash>/`
- **Inside each world folder**:
  - `tiles/` — explored map tiles for each dimension (`overworld`, `the_nether`, `the_end`);
  - `markers.json` — saved markers, colors, coordinates, icons, and descriptions;
  - `regions.json` — created regions and areas;
  - `drawings.json` — hand drawings made on the map;
  - `meta.json` — world or server name, seed, and unique map identifier;
  - screenshots attached to markers.
- **Global configuration**: `.minecraft/config/atlasmapextended.json`.

> [!NOTE]
> **Auto-migration from v1.0:** if you previously used version 1.0 (with the `.minecraft/atlasmap/` folder), version 1.1+ automatically and safely copies all your maps, markers, and settings into the new `atlasmapextended` directory upon first launch.

## Map Switcher and Archiving

The full-screen map toolbar (key `M`) features a folder icon button — **"Switch Map"**:

### 1. Switching Maps In-Game
- The menu lists all saved world and server maps (the active map is marked with a checkmark).
- Click any map in the list to switch to it immediately without leaving the game.
- **"+ New Map for Server"**: if you play on a server that started a new season/wipe, or has multiple sub-worlds/hubs on the same IP address, click this button to create a clean, independent map for that server.
- **"Reset to Default"**: resets the override back to automatic map detection for the current server or world.
- Your selected map override is saved in configuration and persists across game restarts.

### 2. Automatic Map Archiving
The mod protects your maps from accidental overwrites or terrain blending:
- **World deletion**: when you delete a singleplayer world from the Minecraft menu, the map is not permanently lost. It is automatically archived to `local_<world>_old_<timestamp>` and labeled `[Deleted World]`.
- **World recreation or seed change**: if a world is recreated with the same folder name or a different seed, the mod detects the change, archives the previous map as `[Old Seed]`, and starts a fresh map. Old chunks and markers will never blend into the new terrain.
- All archived maps remain safe: they can still be viewed or switched to at any time via the "Switch Map" menu.

## Building

JDK 21 or newer is required.

```bash
./gradlew build
```

The command builds both files in `build/libs/`:

- `AtlasMapExtended-Fabric-1.21.10-v1.1.1.jar`
- `AtlasMapExtended-NeoForge-1.21.10-v1.1.1.jar`

Game tests with screenshots are available on the `26.1` and `26.3` branches (Fabric API for 1.21 does not ship the client-gametest module).

## Authors and Licenses

- **Mod author:** [erkinpaw](https://github.com/erkinpaw) · **License:** [MIT](https://github.com/byMr712/AtlasMapExtended-MinecraftMod/blob/26.3/LICENSE).  
- **Author of the port to Fabric/NeoForge 1.21:** [Mr712](https://github.com/byMr712/AtlasMapExtended-MinecraftMod) · **License:** [Apache License 2.0](LICENSE).
