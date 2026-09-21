# Handoff — Phi Launcher

## 1. Goal

Build **Phi Launcher**, a fork of Prism Launcher that is a distinct product rather than a reskin:

- Playtime statistics (per-day chart, top instances, hour-of-week heatmap, session history), live while playing.
- Full rebrand: name, binary, app id, data directory, logo, copyright, no Prism community links or updater.
- Minecraft-styled UI ("Blocky" theme) whose colors stay editable by the user.
- A dashboard-style main window, card-style settings, and a step-by-step new-instance wizard (design C + wizard from design B).
- **Phi HUD**: an in-game overlay mod shipped with the launcher (FPS, TPS, coordinates, direction, ping, memory, clock, armor, inventory), configurable from the launcher *and* draggable in game, plus a Phi-branded title screen.

## 2. Current state

Repo: `A:\claude\Iota`, branch **`phi`** (remote `upstream` = PrismLauncher; no `origin` yet). 16 commits on top of upstream `43a67faef`.

Everything below is built and was verified on screen unless noted:

- **Stats**: `StatsStore` (`sessions.json`), Stats dialog, Activity column in the main window, live sessions ticked every 30 s, manual refresh buttons.
- **Branding**: `philauncher.exe`, `org.philauncher.PhiLauncher`, `%APPDATA%\PhiLauncher`, `PHILAUNCHER_DATA_DIR`, Φ logo (ICO/ICNS/PNG/SVG), version 1.0.0, one-time import of Prism data on first run.
- **Theme Blocky**: default theme, seeded as an editable copy in `<data>\themes\blocky\` (`theme.json` colors, `themeStyle.css` shapes, `README.txt`); bundled Minecraft font.
- **UI**: Resume hero panel, instance cards, Activity column; settings/new-instance page lists as cards with descriptions and a breadcrumb; 3-step wizard (Source tiles → Version & loader → Name & icon).
- **Auth**: device-code login fixed, self-contained local login page with the Minecraft font.
- **Phi HUD**: Fabric jars for 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2, 26.3 and a Forge 1.8.9 jar, all building; launcher HUD page (install/update/remove, widget settings, in-game position display + reset); "Install Phi HUD" option in the wizard; jars shipped to `build/Debug/phihud/` by the `phihud_jars` target.

Build: `cmake --preset windows_msvc -DCMAKE_PREFIX_PATH=A:/Qt/6.9.3/msvc2022_64 -DENABLE_LTO=OFF` then `cmake --build build --config Debug` (MSVC 2022, Qt 6.9.3, JDK 17 for the Java helper; warnings are errors). Mods: `gradlew build -Pmc=<version>` in `mods/phihud-fabric` (JDK 21), `gradlew build` in `mods/phihud-forge-1.8.9` (JDK 8).

## 3. Active fix

None in progress — the tree is clean and the last build is green.

Open item for the user, not a code change: the instance `main` still has the **v1** Phi HUD jar (20 877 B, no editor/title screen). Click **Edit instance → HUD → Update** to get the v2 jar (80 516 B).

## 4. Changes made

Commits on `phi`, newest first:

| Commit | What |
|---|---|
| `6acd917a8` | Phi HUD v2: in-game drag editor (key H, buttons on title/pause menus), Phi title screen (logo, splash, badge), config v2 with per-widget `x`/`y`, mutual key preservation between mod and launcher, `phihud_jars` copy target |
| `ed8c61f64` | Phi HUD v1: Fabric + Forge mods, `PhiHud` service, HUD instance page, wizard checkbox, Fabric API auto-download from Modrinth |
| `e6019208c` | Manual refresh buttons for stats |
| `fc197b319` | Source tiles fill the grid edge to edge |
| `68a241c53` | Live sessions (open at launch, tick every 30 s, saved per tick) |
| `f460bed68` | Source step shows big tiles |
| `57d8caf6e` | Step-by-step new-instance wizard |
| `9e54775e8` | Dashboard main window, card-style settings and new-instance dialogs |
| `01e630942` | Ignore local `.claude` tooling config |
| `fc7b280d6` | Blocky theme (default, user-editable colors) |
| `7a9ecfb3d` | Minecraft font on the login page |
| `93ebfd0ef` | Auth fix: `Request` marks the task succeeded; local login page replaces the prismlauncher.org redirect |
| `5af51f49b` | Rename to Phi Launcher, Phi logo |
| `9504884e5` | Rebrand (first pass, then renamed), Prism data import, links/updater cleared |
| `bafc3f1d3` | Stats action wired outside the updater block, View-menu entry |
| `633723345` | Playtime statistics: `StatsStore`, charts, Stats dialog |

## 5. Failed attempts

- **Own Microsoft OAuth app**: Azure registration is possible, but the Mojang App ID review form (`aka.ms/mce-reviewappid`) rejects personal accounts ("Selected user account does not exist in tenant 'Microsoft Services'"), and ID@Xbox only handles game publishers. A new client id gets HTTP 403 from `api.minecraftservices.com` until approved, so the build still uses Prism's `Launcher_MSA_CLIENT_ID`. Decision: keep it; retry the form or contact Mojang support later.
- **Qt install attempts**: `aqt install-qt` to `C:\Qt` failed on a permission error writing `C:\aqtinstall.log`; installed to `A:\Qt` instead.
- **First Qt deployment**: `windeployqt --debug` copied the debug DLLs (`Qt6NetworkAuthd.dll`) while the binary wants the release ones → "Qt6NetworkAuth.dll introuvable". Re-ran without `--debug`.
- **Tile grid sizing**: three iterations (spacing arithmetic, then IconMode's own cell spacing, then a scrollbar appearing) before the tiles filled the dialog with no leftover gap; the working version uses `spacing(0)`, a delegate-drawn 3 px gap, `vp/(cols|rows)` minus 4 px and `ScrollBarAlwaysOff`.
- **Fabric Loom 1.18.x**: requires the Gradle JVM itself to be Java 25 — the mod uses Loom 1.17.21 (runs on JDK 21, provisions JDK 25 for compilation).
- **CMake custom target**: `add_custom_target(phihud_jars ...)` broke configuration because the repo applies `target_compile_options` to every `BUILDSYSTEM_TARGETS`; fixed by skipping `UTILITY` targets.
- **26.2.1** does not exist upstream (Mojang lists 26.1, 26.1.1, 26.1.2, 26.2, 26.3) — targets adjusted accordingly.

## 6. Next steps

1. **Test Phi HUD v2 in game** (only the user can): update the jar from the HUD page, check the title screen (logo size/placement, splash, badge), the Phi HUD buttons, the editor's drag/snap feel, and the widgets themselves (TPS estimate, inventory/armor icons). Nothing in v2 was run in a real client.
2. **Instance `totalTimePlayed`** stays frozen while the game runs (the status bar figure). Make it live like the Activity column if wanted.
3. **French strings**: all new UI and the login page are English inside `tr()`; either add translations or hardcode French.
4. **Icon theme**: the user's is Breeze Dark (monochrome); `pe_colored` carries the new colored icons (activity, dashboard, group, play-big, import, custom, stats).
5. **Publishing**: create the GitHub repo, set `origin`, then set `Launcher_UPDATER_GITHUB_REPO`, `Launcher_NEWS_*`, `Launcher_BUG_TRACKER_URL`, `Launcher_MATRIX/DISCORD/SUBREDDIT_URL` (all currently empty, which hides the matching UI). A CI job merging `upstream/develop` and building releases would keep Phi in sync with Prism.
6. **Release build**: only Debug has been built so far.
