# BlockAtlas

A client-side Fabric mod for **Minecraft Java 26.3** that lists every registered block (vanilla and modded) in a dark, card-based browser and highlights any of them through walls within up to **300 m**.

## Features

- Browser of every block with item icon, translated name and id, plus a live search that matches name **or** id (multi-word, e.g. `deep ore`).
- Category tabs: All, Ores, Natural, Building, Redstone, Decorative, Technical, plus **Active** (what you are highlighting).
- Click to highlight; select as many blocks as you like, each with its own colour and opacity.
- Through-wall ESP boxes with bright outlines on the nearest 600, plus a tall **beam** above the nearest instance of each block so you can spot it from afar.
- Live HUD: count found, distance to the nearest one and a **direction arrow** (▲/▼ when above/below you).
- Favourites (★) pinned to the top. Toggles for on/off, through walls and HUD, a range slider (16–300 m), and Clear all.
- Settings persist in `config/blockatlas.json`.

## Freecam

Press **F6** to detach the camera and fly freely; press it again to return. Your character stays where it is (you can see it), and attacking or using items is blocked while flying. Controls: WASD to move, Space up, Shift down, Sprint for 3x speed, mouse to look. Speed is `freecamSpeed` in the config (default 0.6 blocks/tick).

## Controls

| Action | Input |
|---|---|
| Open / close BlockAtlas | **B** (rebindable under Options → Controls → BlockAtlas) |
| Toggle highlights on/off | Unbound by default; bind in Controls |
| Toggle freecam | **F6** |
| Highlight / un-highlight a block | Left-click its card |
| Edit a block's colour without toggling | Right-click its card |
| Favourite | Click the ☆ on a card |
| Focus search | Ctrl+F, or just start typing |
| Clear search / close | Esc |

## Requirements

- Minecraft Java Edition **26.3**
- Fabric Loader **0.19.5+**
- Fabric API **0.161.0+26.3** or newer
- Java **25** (bundled with the official 26.x launcher)

## Build

You need a **JDK 25** (e.g. Temurin 25) to build.

```bash
# Linux / macOS
./gradlew build

# Windows
gradlew.bat build
```

The first build downloads Gradle 9.7.1, Fabric Loom and Minecraft 26.3, which takes a few minutes. The mod jar ends up at:

```
build/libs/blockatlas-1.0.0.jar
```

(ignore `-sources.jar`). To try it in a dev client: `./gradlew runClient`.

## Install

1. Install Fabric Loader 0.19.5+ for Minecraft 26.3 with the [Fabric installer](https://fabricmc.net/use/installer/).
2. Put **Fabric API** (0.161.0+26.3 or newer) in your `.minecraft/mods` folder.
3. Put `blockatlas-1.0.0.jar` in the same `mods` folder.
4. Launch the *fabric-loader-26.3* profile and press **B** in a world.

## Getting the most out of 300 m

The client only knows about chunks it has loaded, so BlockAtlas can never see further than your render distance. For the full 300 m set **Render Distance ≥ 19 chunks** (19 × 16 = 304 m). On servers, the server's view distance also caps this. The range card in the sidebar shows your current loaded radius and says when it is limiting you.

## Performance design

- **Loaded chunks only**, visited nearest-first, so nearby hits show up within a tick or two.
- **Throttled**: scanning runs on the client thread within a time budget per tick (`scanBudgetMicros`, default 3000 µs), so it never stutters and never races chunk updates.
- **Palette skipping**: whole 16×16×16 sections whose palette cannot contain a selected block are skipped without reading any block data, so searching for ores is very cheap.
- Results are re-sorted by distance with an O(n) counting sort, four times a second. Only the nearest `maxRenderedBoxes` (default 6000) are drawn, and storage is capped at `maxResultsPerBlock` (default 50,000; shown as `50,000+`).
- Ranges above 200 m show a warning in the GUI, the HUD and chat.

## Config (`config/blockatlas.json`)

| Key | Default | Meaning |
|---|---|---|
| `range` | 128 | Scan radius in metres (16–300) |
| `enabled` | true | Master on/off |
| `seeThroughWalls` | true | Draw boxes through blocks |
| `showHud` | true | Show the HUD card |
| `outlines` | true | Bright edges on the nearest 600 boxes |
| `nearestBeam` | true | Beam above the nearest instance of each block |
| `scanBudgetMicros` | 3000 | Scan time per tick (µs) |
| `rescanIntervalTicks` | 40 | Pause between full passes |
| `maxRenderedBoxes` | 6000 | Boxes drawn per frame (nearest first) |
| `maxResultsPerBlock` | 50000 | Stored hits per block type |
| `favorites`, `active`, `colors` | | Managed from the GUI |

## Project layout

```
src/main/java/com/yourname/blockatlas
├── BlockAtlasClient.java          entrypoint: keys, tick loop, lifecycle
├── config/BlockAtlasConfig.java   JSON config, favourites, colours
├── gui
│   ├── BlockAtlasScreen.java      main browser screen
│   ├── BlockCatalog.java          all blocks + category classification
│   ├── Category.java              tabs
│   ├── HudOverlay.java            live HUD
│   └── widget/                    Ui (design tokens + drawing), BlockGrid, SearchField, Slider, PillButton
├── highlight
│   ├── HighlightManager.java      selection, results, stats, render list
│   └── BoxRenderer.java           custom through-wall render pipeline
├── scan/BlockScanner.java         incremental, throttled chunk scanner
└── util/ColorUtil.java
```

## Notes for 26.x porting

Minecraft 26.1+ is unobfuscated and uses Mojang names, and Fabric replaced Yarn. A few names differ from older tutorials:

- World rendering uses `LevelExtractionEvents` and `LevelRenderEvents` (the 26.x successors of `WorldRenderEvents`) with a custom `RenderPipeline` and `StagedVertexBuffer`.
- GUI drawing goes through `GuiGraphicsExtractor` and `Screen#extractRenderState`. Screens open with `Minecraft.getInstance().gui.setScreen(...)`.
- Key mappings use `KeyMappingHelper` and `KeyMapping.Category`.

All GUI draw calls go through `gui/widget/Ui.java`, so a renamed method in a future snapshot means a one-line fix there.
