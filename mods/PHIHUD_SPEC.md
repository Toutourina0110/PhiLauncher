# Phi HUD — shared spec (launcher ⇄ mods)

Phi HUD is an in-game overlay shipped with Phi Launcher. The launcher writes a config file into the
instance, offers to install the mod when an instance is created, and the mod renders the widgets and
hot-reloads the config.

## Targets

| Loader | Minecraft            | Artifact                          |
|--------|----------------------|-----------------------------------|
| Fabric | 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2, 26.3 | `phihud-fabric-<mc>.jar` |
| Forge  | 1.8.9                | `phihud-forge-1.8.9.jar`          |

Mod id: `phihud`. Mod name: "Phi HUD". Version: `1.0.0`. Java: 21 for Fabric, 8 for Forge 1.8.9.
Fabric jars depend on Fabric API (the launcher installs it if missing). The Forge jar has no deps.

The launcher looks for jars in `<launcher root>/phihud/` (next to the executable / in the install
prefix). Build outputs are copied there by CMake.

## Config file

Path inside the instance game dir: `config/phihud.json`. The launcher writes it; the mod reads it at
startup and re-reads it whenever its mtime changes (poll every 2 s). Missing file = all defaults.

```json
{
  "version": 1,
  "enabled": true,
  "scale": 1.0,
  "color": "#FFFFFF",
  "background": true,
  "backgroundOpacity": 0.4,
  "shadow": true,
  "margin": 4,
  "widgets": {
    "fps":       { "enabled": true,  "anchor": "top-left",     "order": 0 },
    "tps":       { "enabled": true,  "anchor": "top-left",     "order": 1 },
    "coords":    { "enabled": true,  "anchor": "top-left",     "order": 2 },
    "direction": { "enabled": true,  "anchor": "top-left",     "order": 3 },
    "ping":      { "enabled": false, "anchor": "top-right",    "order": 0 },
    "memory":    { "enabled": false, "anchor": "top-right",    "order": 1 },
    "clock":     { "enabled": false, "anchor": "top-right",    "order": 2 },
    "armor":     { "enabled": false, "anchor": "bottom-left",  "order": 0 },
    "inventory": { "enabled": false, "anchor": "bottom-right", "order": 0 }
  }
}
```

- `anchor` ∈ `top-left | top-right | bottom-left | bottom-right`. Widgets sharing an anchor stack
  vertically in `order` (top anchors stack downward, bottom anchors stack upward).
- `color` is the text color (hex RGB). `scale` multiplies the whole overlay.
- Unknown keys are ignored; unknown widget ids are ignored. Missing widget = its default above.

## Widgets

| id          | Shows                                                                   |
|-------------|-------------------------------------------------------------------------|
| `fps`       | `FPS: 120`                                                              |
| `tps`       | `TPS: 20.0` — client-side estimate from world time packets (server ticks per wall second, 20 = healthy); shows `TPS: --` in single player without a measurement yet |
| `coords`    | `XYZ: 123.4 / 64.0 / -87.2` (1 decimal)                                 |
| `direction` | `Facing: North (-Z)` + yaw/pitch `(87.3 / -12.0)`                        |
| `ping`      | `Ping: 34 ms` (own player ping from the tab list; `--` in single player) |
| `memory`    | `Mem: 1234 / 4096 MB (30%)`                                             |
| `clock`     | Real time `14:05`                                                       |
| `armor`     | The 4 armor slots + held item as icons with durability bars             |
| `inventory` | 9×3 grid of the main inventory (icons + stack counts), like the inventory screen but without the hotbar |

Text widgets are one line each, drawn with the vanilla font; `background` draws a translucent box
behind each anchor group. Nothing is shown while a screen (menu/inventory) is open or when the debug
screen (F3) is open. `enabled: false` at the root disables everything.

## Launcher side

- Instance page **HUD** (in the instance edit window, icon `dashboard`): master toggle, per-widget
  checkbox + anchor combo, scale, color, background toggle/opacity, shadow. Saves `config/phihud.json`
  on Apply. Shows the install state: "Phi HUD 1.0.0 installed" / "Not installed" + button
  **Install** / **Update** / **Remove** (copies/removes `mods/phihud-*.jar`; for Fabric also ensures
  Fabric API is present, downloading it from Modrinth for the instance's Minecraft version if not).
  Unsupported instance (other loader/version) → page explains which loader/version are supported.
- New-instance wizard step 3: checkbox "Install Phi HUD (in-game overlay)", visible only when the
  chosen source is Custom/Vanilla with a supported loader+version; checked by default. Installation
  happens after the instance is created.

## v2 — in-game editor and Phi title screen

### Config schema v2 (`"version": 2`)

Each widget may carry a free position set from the in-game editor:

```json
"fps": { "enabled": true, "anchor": "top-left", "order": 0, "x": 0.02, "y": 0.05 }
```

- `x`, `y` are fractions (0.0–1.0) of the *scaled* screen size, pointing at the widget's pivot corner.
  The pivot is chosen from the position: `x < 0.5` → left edge, else right edge; `y < 0.5` → top edge,
  else bottom edge. This keeps layouts stable across resolutions and GUI scales.
- When `x`/`y` are absent the widget falls back to v1 anchor stacking (`anchor` + `order`).
- Widgets with a free position are never stacked; each is drawn at its own spot. Background box is
  per widget in that case.
- Snapping: while dragging in the editor, snap to screen edges / center lines within 4 scaled px.
- Readers must ignore unknown keys and **writers must preserve keys they do not understand**
  (read-modify-write). The launcher only updates the fields shown in its HUD page and keeps
  `x`/`y`; the mod only updates `enabled`/`x`/`y` and keeps the rest.
- `"version": 1` files are accepted as-is (no `x`/`y` → anchor mode).

### In-game editor screen (Fabric and Forge)

- Opened by: keybind **H** (category "Phi HUD", rebindable), the **Phi HUD** button on the title
  screen and on the pause menu.
- Shows every widget (enabled or not) as a draggable box with its live content; disabled widgets
  are drawn at 40% opacity with a "(off)" suffix. Left-drag moves; right-click toggles enabled.
  Snapping as above. A small toolbar at the bottom: **Reset layout** (removes all `x`/`y`),
  **Done** (saves + closes), and a checkbox **Show overlay** (root `enabled`).
- Escape = save + close. The editor saves to `config/phihud.json` immediately on every change
  (write is cheap) so the launcher page sees it.

### Phi title screen (Fabric and Forge)

- The vanilla logo is replaced by the Phi logo (ship `assets/phihud/textures/gui/phi_logo.png`,
  the launcher's Φ mark on transparent, ~512×256, drawn centered at the same place/size as the
  vanilla logo) and the splash text is replaced by a Phi splash ("Powered by Phi Launcher").
- A **Phi HUD** button is added under the vanilla buttons (same width as "Options", full width
  row) opening the editor. A small "Phi Launcher" text badge sits bottom-left above the version.
- The pause menu gets a **Phi HUD** button below "Options…" (same width as that row).
- Everything else (panorama, buttons, realms, etc.) stays vanilla. Implement with mixins/events on
  the vanilla screens; do not replace the screen classes.

## v3 — mod menu and logo fix

### Logo (bug)

The current draw uses a *region* blit: `blit(pipeline, LOGO, x, y, 0, 0, 192, 96, 512, 256)` takes the
top-left 192×96 **pixels** of the 512×256 texture instead of scaling the whole image, so the title
screen shows a huge cropped corner. Fix by shipping the logo at exactly the size it is drawn:
`assets/phihud/textures/gui/phi_logo.png` becomes **128×128**, the Φ mark centered with a small
margin, and it is drawn 128×128 (`u=v=0`, region = 128×128, texture = 128×128), horizontally
centered, sitting above the button block (bottom edge ≈ 8 px above the first button). Verify the
blit overload in each target version with `javap`; when in doubt use the overload whose region size
and texture size are all equal so no scaling math is involved.

### Phi HUD menu (replaces the bare editor)

A launcher-style mod menu (think Lunar/Feather), opened by **H** or the **Phi HUD** buttons:

- **Left panel** (≈140 px): the list of widgets ("mods"), one row each: name + an ON/OFF switch drawn
  as a small pill (green when on, grey when off). Clicking the row selects it, clicking the switch
  toggles it. A search box sits above the list. A "Settings" row at the bottom opens the global
  settings (scale, color, background + opacity, shadow, margin, master toggle).
- **Right area**: the live game view with every enabled widget drawn where it will actually be; the
  selected widget is outlined and can be dragged (snapping as in v2). Dragging is the only way to
  place widgets; the corner/anchor stays the fallback for widgets never dragged.
- **Per-widget settings**, shown under the list when a widget is selected: its own `scale`
  (0.5–3, default = inherit global), `color` (hex, default = inherit), `background` (inherit / on /
  off). These are optional keys `scale`, `color`, `background` inside the widget object; absent =
  inherit the global value.
- **Bottom bar**: Reset layout · Done. Escape saves and closes.
- The whole menu is drawn with the vanilla widget style (`Button`, `EditBox`, `AbstractSelectionList`
  on Fabric; hand-drawn equivalents on 1.8.9), dark translucent panel background.

Config additions (still `"version": 2`, all optional, inherit when absent):

```json
"fps": { "enabled": true, "anchor": "top-left", "order": 0, "x": 0.02, "y": 0.05,
         "scale": 1.5, "color": "#55FF55", "background": false }
```
