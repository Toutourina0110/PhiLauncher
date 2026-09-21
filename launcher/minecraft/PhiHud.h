// SPDX-License-Identifier: GPL-3.0-only
/*
 *  Phi Launcher - Minecraft Launcher
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, version 3.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#pragma once

#include <cmath>
#include <limits>

#include <QMap>
#include <QString>
#include <QStringList>
#include <optional>

#include "tasks/Task.h"

class MinecraftInstance;

/// Phi HUD: the in-game overlay mod shipped with the launcher (see mods/PHIHUD_SPEC.md).
namespace PhiHud {

inline const QString VERSION = QStringLiteral("1.0.0");

/// Where the launcher ships the mod jars: `<launcher root>/phihud/`.
QString jarsDir();

struct Support {
    QString loader;     // component uid: net.fabricmc.fabric-loader / net.minecraftforge
    QString mcVersion;  // e.g. "1.21.11"
    QString jarName;    // e.g. "phihud-fabric-1.21.11.jar"
};

/// Which jar (if any) targets this loader + Minecraft version.
std::optional<Support> supportFor(const QString& loader, const QString& mcVersion);
std::optional<Support> supportFor(MinecraftInstance* inst);
/// Human list of supported targets, e.g. "Fabric 1.21.11 / 26.2 or Forge 1.8.9".
QString supportedTargets();

/// The jar for this target exists in jarsDir().
bool jarAvailable(const Support& support);
/// Path of a `phihud-*.jar` in the instance's mods folder, or empty.
QString installedJar(MinecraftInstance* inst);
bool isInstalled(MinecraftInstance* inst);

/// Copies the jar into mods/ (replacing any older phihud jar); for Fabric also downloads Fabric API
/// from Modrinth when no `fabric-api*.jar` is present. Returns nullptr if the instance is unsupported.
Task::Ptr installTask(MinecraftInstance* inst);
/// Deletes `phihud-*.jar` from mods/ (Fabric API is left alone).
void remove(MinecraftInstance* inst);

struct WidgetConfig {
    bool enabled = false;
    QString anchor = "top-left";  // top-left | top-right | bottom-left | bottom-right
    int order = 0;
    // free position set from the in-game editor (fractions of the screen); NaN = not set
    double x = std::numeric_limits<double>::quiet_NaN();
    double y = std::numeric_limits<double>::quiet_NaN();
    bool hasPosition() const { return !std::isnan(x) && !std::isnan(y); }
};

struct HudConfig {
    bool enabled = true;
    double scale = 1.0;
    QString color = "#FFFFFF";
    bool background = true;
    double backgroundOpacity = 0.4;
    bool shadow = true;
    int margin = 4;
    QMap<QString, WidgetConfig> widgets;  // keyed by widget id, see widgetIds()

    HudConfig();  // spec defaults
};

/// Widget ids in spec order.
const QStringList& widgetIds();
/// Human name for a widget id ("fps" -> "FPS").
QString widgetName(const QString& id);
/// Valid anchors in display order.
const QStringList& anchors();
/// Human name for an anchor ("top-left" -> "Top left").
QString anchorName(const QString& anchor);

QString configPath(MinecraftInstance* inst);
/// Missing or unreadable file -> defaults. Unknown keys / widget ids are ignored.
HudConfig readConfig(MinecraftInstance* inst);
bool writeConfig(MinecraftInstance* inst, const HudConfig& config);

}  // namespace PhiHud
