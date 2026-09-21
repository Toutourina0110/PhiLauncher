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

#include "PhiHud.h"

#include <QDebug>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonObject>
#include <QUrl>
#include <utility>

#include "Application.h"
#include "BuildConfig.h"
#include "FileSystem.h"
#include "Json.h"
#include "minecraft/MinecraftInstance.h"
#include "minecraft/PackProfile.h"
#include "net/ApiRequest.h"
#include "net/NetJob.h"

namespace PhiHud {

namespace {

const QString FABRIC = "net.fabricmc.fabric-loader";
const QString FORGE = "net.minecraftforge";
// The single source of truth for supported targets (mods/PHIHUD_SPEC.md).
const QStringList FABRIC_VERSIONS = { "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3" };
const QStringList FORGE_VERSIONS = { "1.8.9" };

QStringList findJars(const QString& dir, const QString& pattern)
{
    QStringList out;
    for (const auto& name : QDir(dir).entryList({ pattern }, QDir::Files))
        out << FS::PathCombine(dir, name);
    return out;
}

/// Copies the jar, then (Fabric only) fetches Fabric API from Modrinth if missing.
class InstallTask : public Task {
    Q_OBJECT
   public:
    InstallTask(MinecraftInstance* inst, Support support) : m_modsDir(inst->modsRoot()), m_support(std::move(support)) {}

    bool canAbort() const override { return true; }
    bool abort() override
    {
        if (m_net && m_net->canAbort())
            return m_net->abort();
        return Task::abort();
    }

   protected:
    void executeTask() override
    {
        setStatus(tr("Installing Phi HUD"));
        FS::ensureFolderPathExists(m_modsDir);
        for (const auto& old : findJars(m_modsDir, "phihud-*.jar"))
            QFile::remove(old);
        auto src = FS::PathCombine(jarsDir(), m_support.jarName);
        if (!QFile::copy(src, FS::PathCombine(m_modsDir, m_support.jarName))) {
            emitFailed(tr("Could not copy %1 into the mods folder.").arg(src));
            return;
        }
        if (m_support.loader != FABRIC || !findJars(m_modsDir, "fabric-api*.jar").isEmpty()) {
            emitSucceeded();
            return;
        }

        setStatus(tr("Looking up Fabric API"));
        QUrl url(QString(R"(%1/project/fabric-api/version?game_versions=["%2"]&loaders=["fabric"])")
                     .arg(BuildConfig.MODRINTH_PROD_URL, m_support.mcVersion));
        auto [request, response] = Net::ApiRequest::makeByteArray(url);
        m_net = makeShared<NetJob>("PhiHud::FabricApiLookup", APPLICATION->network());
        m_net->addNetAction(request);
        connect(m_net.get(), &Task::failed, this, &InstallTask::emitFailed);
        connect(m_net.get(), &Task::aborted, this, &InstallTask::emitAborted);
        connect(m_net.get(), &Task::succeeded, this, [this, response] { downloadFabricApi(*response); });
        m_net->start();
    }

   private:
    void downloadFabricApi(const QByteArray& json)
    {
        auto versions = Json::requireArray(json, "Fabric API versions");
        if (!versions) {
            emitFailed(versions.error());
            return;
        }
        if (versions->isEmpty()) {
            emitFailed(tr("No Fabric API release found for Minecraft %1.").arg(m_support.mcVersion));
            return;
        }
        auto files = versions->first().toObject()["files"].toArray();
        auto file = files.isEmpty() ? QJsonObject() : files.first().toObject();
        auto fileUrl = file["url"].toString();
        auto fileName = file["filename"].toString();
        if (fileUrl.isEmpty() || fileName.isEmpty()) {
            emitFailed(tr("Modrinth returned no file for Fabric API."));
            return;
        }

        setStatus(tr("Downloading %1").arg(fileName));
        m_net = makeShared<NetJob>("PhiHud::FabricApiDownload", APPLICATION->network());
        m_net->addNetAction(Net::ApiRequest::makeFile(QUrl(fileUrl), FS::PathCombine(m_modsDir, fileName)));
        connect(m_net.get(), &Task::failed, this, &InstallTask::emitFailed);
        connect(m_net.get(), &Task::aborted, this, &InstallTask::emitAborted);
        connect(m_net.get(), &Task::progress, this, &InstallTask::setProgress);
        connect(m_net.get(), &Task::succeeded, this, &InstallTask::emitSucceeded);
        m_net->start();
    }

    QString m_modsDir;
    Support m_support;
    NetJob::Ptr m_net;
};

}  // namespace

QString jarsDir()
{
    return FS::PathCombine(APPLICATION->root(), "phihud");
}

std::optional<Support> supportFor(const QString& loader, const QString& mcVersion)
{
    if (loader == FABRIC && FABRIC_VERSIONS.contains(mcVersion))
        return Support{ loader, mcVersion, QString("phihud-fabric-%1.jar").arg(mcVersion) };
    if (loader == FORGE && FORGE_VERSIONS.contains(mcVersion))
        return Support{ loader, mcVersion, QString("phihud-forge-%1.jar").arg(mcVersion) };
    return std::nullopt;
}

std::optional<Support> supportFor(MinecraftInstance* inst)
{
    auto* profile = inst->getPackProfile();
    // not loaded yet (and no update task owning the profile, which reload() would abort)
    if (profile->getComponentVersion("net.minecraft").isEmpty() && !profile->getCurrentTask()) {
        if (auto res = profile->reload(Net::Mode::Offline); !res)
            qWarning() << "PhiHud: failed to load components:" << res.error();
    }
    auto mc = profile->getComponentVersion("net.minecraft");
    for (const auto& loader : { FABRIC, FORGE }) {
        if (!profile->getComponentVersion(loader).isEmpty())
            return supportFor(loader, mc);
    }
    return std::nullopt;
}

QString supportedTargets()
{
    return QObject::tr("Fabric %1 or Forge %2").arg(FABRIC_VERSIONS.join(" / "), FORGE_VERSIONS.join(" / "));
}

bool jarAvailable(const Support& support)
{
    return QFile::exists(FS::PathCombine(jarsDir(), support.jarName));
}

QString installedJar(MinecraftInstance* inst)
{
    auto jars = findJars(inst->modsRoot(), "phihud-*.jar");
    return jars.isEmpty() ? QString() : jars.first();
}

bool isInstalled(MinecraftInstance* inst)
{
    return !installedJar(inst).isEmpty();
}

Task::Ptr installTask(MinecraftInstance* inst)
{
    auto support = supportFor(inst);
    if (!support)
        return nullptr;
    return makeShared<InstallTask>(inst, *support);
}

void remove(MinecraftInstance* inst)
{
    for (const auto& jar : findJars(inst->modsRoot(), "phihud-*.jar"))
        QFile::remove(jar);
}

// ---- config ----

namespace {
struct WidgetInfo {
    const char* id;
    const char* name;  // tr() source
    bool enabled;
    const char* anchor;
    int order;
};
// Spec order; defaults from the spec JSON.
const WidgetInfo WIDGETS[] = {
    { "fps", "FPS", true, "top-left", 0 },
    { "tps", "TPS", true, "top-left", 1 },
    { "coords", "Coordinates", true, "top-left", 2 },
    { "direction", "Direction", true, "top-left", 3 },
    { "ping", "Ping", false, "top-right", 0 },
    { "memory", "Memory", false, "top-right", 1 },
    { "clock", "Clock", false, "top-right", 2 },
    { "armor", "Armor", false, "bottom-left", 0 },
    { "inventory", "Inventory", false, "bottom-right", 0 },
    // v4
    { "keystrokes", "Keystrokes", false, "bottom-left", 1 },
    { "cps", "CPS", false, "bottom-left", 2 },
    { "speed", "Speed", false, "top-left", 4 },
    { "biome", "Biome", false, "top-left", 5 },
    { "gametime", "Game time", false, "top-right", 3 },
    { "light", "Light level", false, "top-left", 6 },
    { "target", "Target", false, "top-left", 7 },
    { "server", "Server", false, "top-right", 4 },
    { "session", "Session time", false, "top-right", 5 },
    { "effects", "Potion effects", false, "top-right", 6 },
    { "hunger", "Hunger", false, "bottom-left", 3 },
    { "health", "Health", false, "bottom-left", 4 },
};
const QStringList ANCHORS = { "top-left", "top-right", "bottom-left", "bottom-right" };
}  // namespace

HudConfig::HudConfig()
{
    for (const auto& w : WIDGETS)
        widgets[w.id] = WidgetConfig{ w.enabled, w.anchor, w.order };
}

const QStringList& widgetIds()
{
    static const QStringList ids = [] {
        QStringList out;
        for (const auto& w : WIDGETS)
            out << w.id;
        return out;
    }();
    return ids;
}

QString widgetName(const QString& id)
{
    for (const auto& w : WIDGETS)
        if (id == w.id)
            return QObject::tr(w.name);
    return id;
}

const QStringList& anchors()
{
    return ANCHORS;
}

QString anchorName(const QString& anchor)
{
    if (anchor == "top-left")
        return QObject::tr("Top left");
    if (anchor == "top-right")
        return QObject::tr("Top right");
    if (anchor == "bottom-left")
        return QObject::tr("Bottom left");
    if (anchor == "bottom-right")
        return QObject::tr("Bottom right");
    return anchor;
}

QString configPath(MinecraftInstance* inst)
{
    return FS::PathCombine(inst->gameRoot(), "config", "phihud.json");
}

HudConfig readConfig(MinecraftInstance* inst)
{
    HudConfig cfg;
    auto path = configPath(inst);
    if (!QFile::exists(path))
        return cfg;
    auto root = Json::requireObject(path, "phihud.json");
    if (!root) {
        qWarning() << "PhiHud: unreadable config" << path << root.error();
        return cfg;
    }
    const auto& o = *root;
    cfg.enabled = o["enabled"].toBool(cfg.enabled);
    cfg.scale = o["scale"].toDouble(cfg.scale);
    cfg.color = o["color"].toString(cfg.color);
    cfg.background = o["background"].toBool(cfg.background);
    cfg.backgroundOpacity = o["backgroundOpacity"].toDouble(cfg.backgroundOpacity);
    cfg.shadow = o["shadow"].toBool(cfg.shadow);
    cfg.margin = o["margin"].toInt(cfg.margin);
    auto widgets = o["widgets"].toObject();
    for (auto it = cfg.widgets.begin(); it != cfg.widgets.end(); ++it) {
        auto w = widgets[it.key()].toObject();
        it->enabled = w["enabled"].toBool(it->enabled);
        auto anchor = w["anchor"].toString();
        if (ANCHORS.contains(anchor))
            it->anchor = anchor;
        it->order = w["order"].toInt(it->order);
        if (w.contains("x") && w.contains("y")) {
            it->x = w["x"].toDouble();
            it->y = w["y"].toDouble();
        }
    }
    return cfg;
}

bool writeConfig(MinecraftInstance* inst, const HudConfig& cfg)
{
    auto path = configPath(inst);
    // read-modify-write: the in-game editor owns keys we do not show (and future ones)
    QJsonObject root;
    if (QFile::exists(path)) {
        if (auto existing = Json::requireObject(path, "phihud.json"))
            root = *existing;
    }
    QJsonObject widgets = root["widgets"].toObject();
    for (auto it = cfg.widgets.cbegin(); it != cfg.widgets.cend(); ++it) {
        QJsonObject w = widgets[it.key()].toObject();
        w["enabled"] = it->enabled;
        w["anchor"] = it->anchor;
        w["order"] = it->order;
        if (it->hasPosition()) {
            w["x"] = it->x;
            w["y"] = it->y;
        } else {
            w.remove("x");
            w.remove("y");
        }
        widgets[it.key()] = w;
    }
    root["version"] = 2;
    root["enabled"] = cfg.enabled;
    root["scale"] = cfg.scale;
    root["color"] = cfg.color;
    root["background"] = cfg.background;
    root["backgroundOpacity"] = cfg.backgroundOpacity;
    root["shadow"] = cfg.shadow;
    root["margin"] = cfg.margin;
    root["widgets"] = widgets;
    FS::ensureFilePathExists(path);
    if (auto res = Json::write(root, path); !res) {
        qWarning() << "PhiHud: failed to write" << path << res.error();
        return false;
    }
    return true;
}

}  // namespace PhiHud

#include "PhiHud.moc"
