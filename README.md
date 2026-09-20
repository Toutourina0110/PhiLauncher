# Iota

Iota is a Minecraft launcher based on [Prism Launcher](https://github.com/PrismLauncher/PrismLauncher), with built-in playtime statistics (per-day charts, top instances, hour-of-week heatmap, session history).

On first launch, Iota offers to import existing Prism Launcher data (instances, accounts, settings). The two launchers keep separate data folders afterwards.

## Building

Same toolchain as Prism Launcher: CMake, Qt 6.8+, a C++23 compiler and a JDK. On Windows with MSVC:

```
cmake --preset windows_msvc -DCMAKE_PREFIX_PATH=<path to Qt>/msvc2022_64
cmake --build build --config Release
```

## License

GPL-3.0-only. See [COPYING.md](COPYING.md). Iota carries the copyright of the Prism Launcher, PolyMC and MultiMC contributors whose work it builds on.
