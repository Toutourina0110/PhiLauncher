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

#include "PhiHudPage.h"

#include <algorithm>
#include <QLabel>
#include <limits>
#include "ui_PhiHudPage.h"

#include <QCheckBox>
#include <QColor>
#include <QColorDialog>
#include <QComboBox>
#include <QFile>

#include "minecraft/MinecraftInstance.h"
#include "ui/dialogs/CustomMessageBox.h"
#include "ui/dialogs/ProgressDialog.h"

PhiHudPage::PhiHudPage(MinecraftInstance* inst, QWidget* parent) : QWidget(parent), m_ui(new Ui::PhiHudPage), m_inst(inst)
{
    m_ui->setupUi(this);

    int row = 0;
    for (const auto& id : PhiHud::widgetIds()) {
        auto* enabled = new QCheckBox(PhiHud::widgetName(id), this);
        auto* anchor = new QComboBox(this);
        for (const auto& a : PhiHud::anchors())
            anchor->addItem(PhiHud::anchorName(a), a);
        auto* placed = new QLabel(m_ui->widgetsGroup);
        placed->setForegroundRole(QPalette::Mid);
        m_ui->widgetsLayout->addWidget(enabled, row, 0);
        m_ui->widgetsLayout->addWidget(anchor, row, 1);
        m_ui->widgetsLayout->addWidget(placed, row, 2);
        m_rows.append({ id, enabled, anchor, placed });
        ++row;
    }
    m_ui->widgetsLayout->setColumnStretch(2, 1);

    connect(m_ui->installButton, &QPushButton::clicked, this, &PhiHudPage::install);
    connect(m_ui->updateButton, &QPushButton::clicked, this, &PhiHudPage::install);
    connect(m_ui->removeButton, &QPushButton::clicked, this, &PhiHudPage::remove);
    connect(m_ui->colorButton, &QPushButton::clicked, this, &PhiHudPage::pickColor);
    connect(m_ui->resetPositionsButton, &QPushButton::clicked, this, &PhiHudPage::resetPositions);
    connect(m_ui->backgroundBox, &QCheckBox::toggled, m_ui->opacitySpin, &QWidget::setEnabled);
}

PhiHudPage::~PhiHudPage()
{
    delete m_ui;
}

void PhiHudPage::openedImpl()
{
    refreshStatus();
    m_loaded = PhiHud::readConfig(m_inst);
    load(m_loaded);
    m_visited = true;
}

bool PhiHudPage::apply()
{
    // don't litter every instance with a config file: only write once the user has seen the page
    if (!m_visited)
        return true;
    return PhiHud::writeConfig(m_inst, collect());
}

void PhiHudPage::retranslate()
{
    m_ui->retranslateUi(this);
    refreshStatus();
}

void PhiHudPage::refreshStatus()
{
    auto support = PhiHud::supportFor(m_inst);
    bool installed = PhiHud::isInstalled(m_inst);
    bool available = support && PhiHud::jarAvailable(*support);

    QString text;
    if (!support)
        text = tr("Not supported: needs %1").arg(PhiHud::supportedTargets());
    else if (installed)
        text = tr("Phi HUD %1 installed").arg(PhiHud::VERSION);
    else if (!available)
        text = tr("Jar missing from the launcher install");
    else
        text = tr("Not installed");
    m_ui->statusLabel->setText(text);

    m_ui->installButton->setVisible(available && !installed);
    m_ui->updateButton->setVisible(available && installed);
    m_ui->removeButton->setVisible(installed);
    m_ui->scrollArea->setEnabled(support.has_value());
}

void PhiHudPage::install()
{
    auto task = PhiHud::installTask(m_inst);
    if (!task)
        return;
    ProgressDialog dlg(this);
    dlg.setSkipButton(true, tr("Abort"));
    dlg.execWithTask(task.get());
    if (task->wasSuccessful()) {
        if (!QFile::exists(PhiHud::configPath(m_inst)))
            PhiHud::writeConfig(m_inst, collect());
    } else if (!task->failReason().isEmpty()) {
        CustomMessageBox::selectable(this, tr("Error"), task->failReason(), QMessageBox::Critical)->show();
    }
    refreshStatus();
}

void PhiHudPage::remove()
{
    PhiHud::remove(m_inst);
    refreshStatus();
}

void PhiHudPage::pickColor()
{
    auto color = QColorDialog::getColor(QColor(m_ui->colorEdit->text()), this, tr("HUD text color"));
    if (color.isValid())
        m_ui->colorEdit->setText(color.name(QColor::HexRgb).toUpper());
}

void PhiHudPage::load(const PhiHud::HudConfig& cfg)
{
    m_ui->enabledBox->setChecked(cfg.enabled);
    m_ui->scaleSpin->setValue(cfg.scale);
    m_ui->colorEdit->setText(cfg.color);
    m_ui->backgroundBox->setChecked(cfg.background);
    m_ui->opacitySpin->setValue(cfg.backgroundOpacity);
    m_ui->opacitySpin->setEnabled(cfg.background);
    m_ui->shadowBox->setChecked(cfg.shadow);
    m_ui->marginSpin->setValue(cfg.margin);
    for (const auto& r : m_rows) {
        const auto w = cfg.widgets.value(r.id);
        r.enabled->setChecked(w.enabled);
        r.anchor->setCurrentIndex(qMax(0, r.anchor->findData(w.anchor)));
        r.anchor->setEnabled(!w.hasPosition());
        r.placed->setText(w.hasPosition() ? tr("placed in game (%1%, %2%)").arg(qRound(w.x * 100)).arg(qRound(w.y * 100)) : QString());
    }
    bool anyPlaced = std::any_of(cfg.widgets.cbegin(), cfg.widgets.cend(), [](const PhiHud::WidgetConfig& w) { return w.hasPosition(); });
    m_ui->resetPositionsButton->setEnabled(anyPlaced);
}

void PhiHudPage::resetPositions()
{
    for (auto& w : m_loaded.widgets)
        w.x = w.y = std::numeric_limits<double>::quiet_NaN();
    load(m_loaded);
}

PhiHud::HudConfig PhiHudPage::collect() const
{
    PhiHud::HudConfig cfg = m_loaded;
    cfg.enabled = m_ui->enabledBox->isChecked();
    cfg.scale = m_ui->scaleSpin->value();
    auto color = QColor(m_ui->colorEdit->text());
    cfg.color = color.isValid() ? color.name(QColor::HexRgb).toUpper() : QString("#FFFFFF");
    cfg.background = m_ui->backgroundBox->isChecked();
    cfg.backgroundOpacity = m_ui->opacitySpin->value();
    cfg.shadow = m_ui->shadowBox->isChecked();
    cfg.margin = m_ui->marginSpin->value();
    for (const auto& r : m_rows) {
        auto& w = cfg.widgets[r.id];
        w.enabled = r.enabled->isChecked();
        w.anchor = r.anchor->currentData().toString();
    }
    return cfg;
}
