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
