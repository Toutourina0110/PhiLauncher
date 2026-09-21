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

#include <QWidget>

#include "minecraft/PhiHud.h"
#include "ui/pages/BasePage.h"

class MinecraftInstance;
class QCheckBox;
class QComboBox;

namespace Ui {
class PhiHudPage;
}

/// Instance page: install state of the Phi HUD mod + editor for config/phihud.json.
class PhiHudPage : public QWidget, public BasePage {
    Q_OBJECT

   public:
    explicit PhiHudPage(MinecraftInstance* inst, QWidget* parent = nullptr);
    ~PhiHudPage() override;

    QString displayName() const override { return tr("HUD"); }
    QIcon icon() const override { return QIcon::fromTheme("dashboard"); }
    QString id() const override { return "phihud"; }
    QString description() const override { return tr("In-game overlay: FPS, TPS, coordinates, inventory…"); }
    QString helpPage() const override { return "Phi-HUD"; }
    bool apply() override;
    void openedImpl() override;
    void retranslate() override;

   private slots:
    void install();
    void remove();
    void pickColor();
    void resetPositions();

   private:
    void refreshStatus();
    void load(const PhiHud::HudConfig& config);
    PhiHud::HudConfig collect() const;

    struct WidgetRow {
        QString id;
        QCheckBox* enabled;
        QComboBox* anchor;
        class QLabel* placed;
    };

    Ui::PhiHudPage* m_ui;
    MinecraftInstance* m_inst;
    QList<WidgetRow> m_rows;
    PhiHud::HudConfig m_loaded;  // keeps widget order, which the page doesn't edit
    bool m_visited = false;
};
