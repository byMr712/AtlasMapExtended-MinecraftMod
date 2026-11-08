> **Language:** [Русский](README.md) · English

<div align="center">

![Java 25+](https://img.shields.io/badge/Java-25+-blue.svg)
![Minecraft](https://img.shields.io/badge/Minecraft-26.1-blue.svg)
![Fabric](https://img.shields.io/badge/Loader-Fabric-blue.svg)
![NeoForge](https://img.shields.io/badge/Loader-NeoForge-blue.svg)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

  <img width="150" alt="Atlas Map (Extended) logo" src="images/icon.png">
  <h3>Atlas Map (Extended)</h3>

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
- **Map Atlas** in the main menu: maps, markers, and screenshots are stored separately for each server and world.
- Interface in Russian and English.

## Installation

The mod runs on **Fabric** and **NeoForge**. Fabric API and other libraries are not required.

1. Install [Fabric Loader](https://fabricmc.net/use/) (0.20.1 or newer) or [NeoForge](https://neoforged.net/) for Minecraft **26.1**.
2. Place one JAR for your loader into the `mods` folder:
   - `AtlasMapExtended-Fabric-26.1-v1.0.jar` for Fabric;
   - `AtlasMapExtended-NeoForge-26.1-v1.0.jar` for NeoForge.
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

## Data Storage

`.minecraft/atlasmapextended/worlds/<id>/`: map tiles, markers (`markers.json`), drawings (`drawings.json`), and marker screenshots. Settings are in `.minecraft/config/atlasmapextended.json`.

## Building

JDK 25 or newer is required.

```bash
./gradlew build
```

The command builds both files in `build/libs/`:

- `AtlasMapExtended-Fabric-26.1-v1.0.jar`
- `AtlasMapExtended-NeoForge-26.1-v1.0.jar`

Game tests with screenshots are run with `./gradlew runClientGameTest`; screenshots are saved to `build/run/clientGameTest/screenshots/`.

## Authors and Licenses

- **Mod author:** [erkinpaw](https://github.com/erkinpaw) · **License:** [MIT](https://github.com/byMr712/AtlasMapExtended-MinecraftMod/blob/26.3/LICENSE).  
- **Author of the port to Fabric/NeoForge 1.21 - 26.1:** [Mr712](https://github.com/byMr712/AtlasMapExtended-MinecraftMod) · **License:** [Apache License 2.0](LICENSE).

