// SPDX-License-Identifier: GPL-3.0-only
/*
 *  Prism Launcher - Minecraft Launcher
 *  Copyright (C) 2025 TheKodeToad <TheKodeToad@proton.me>
 *  Copyright (C) 2022 Tayou <git@tayou.org>
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
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 *      Copyright 2013-2021 MultiMC Contributors
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

#include "AppearanceWidget.h"
#include "ui_AppearanceWidget.h"

#include <DesktopServices.h>
#include <Json.h>
#include <QColorDialog>
#include <QGraphicsOpacityEffect>
#include <QLineEdit>
#include <QRegularExpressionValidator>
#include <QToolButton>
#include "BuildConfig.h"
#include "ui/themes/ITheme.h"
#include "ui/themes/ThemeManager.h"

#include <Application.h>
#include "settings/SettingsObject.h"

// theme.json palette keys exposed in the "Phi" color grid, in display order
static const QStringList s_paletteKeys = { "Window", "WindowText", "Base",     "AlternateBase", "Text",
                                           "Button", "ButtonText", "Highlight", "HighlightedText", "Link",
                                           "Light",  "Midlight",   "Mid",      "Dark",          "PlaceholderText" };

static const QString s_editableTheme = QStringLiteral("blocky");

static QString themeJsonPath()
{
    return APPLICATION->themeManager()->getApplicationThemesFolder().filePath(QString("%1/theme.json").arg(s_editableTheme));
}

static void setSwatchColor(QToolButton* swatch, const QColor& color)
{
    QPixmap pixmap(16, 16);
    pixmap.fill(color);
    swatch->setIcon(QIcon(pixmap));
    swatch->setToolTip(color.name());
}

AppearanceWidget::AppearanceWidget(bool themesOnly, QWidget* parent)
    : QWidget(parent), m_ui(new Ui::AppearanceWidget), m_themesOnly(themesOnly)
{
    m_ui->setupUi(this);
    buildColorGrid();

    connect(m_ui->enableCatCheckBox, &QCheckBox::toggled, m_ui->catSettingsBox, &QWidget::setEnabled);

    m_ui->catPreview->setGraphicsEffect(new QGraphicsOpacityEffect(this));

    m_defaultFormat = QTextCharFormat(m_ui->consolePreview->currentCharFormat());

    if (themesOnly) {
        m_ui->catPackLabel->hide();
        m_ui->catPackComboBox->hide();
        m_ui->catPackFolder->hide();
        m_ui->phiBox->hide();
        m_ui->settingsBox->hide();
        m_ui->consolePreview->hide();
        m_ui->catPreview->hide();
        loadThemeSettings();
    } else {
        loadSettings();
        loadThemeSettings();

        updateConsolePreview();
        updateCatPreview();
    }

    connect(m_ui->fontSizeBox, &QSpinBox::valueChanged, this, &AppearanceWidget::updateConsolePreview);
    connect(m_ui->consoleFont, &QFontComboBox::currentFontChanged, this, &AppearanceWidget::updateConsolePreview);

    connect(m_ui->iconsComboBox, &QComboBox::currentIndexChanged, this, &AppearanceWidget::applyIconTheme);
    connect(m_ui->widgetStyleComboBox, &QComboBox::currentIndexChanged, this, &AppearanceWidget::applyWidgetTheme);
    connect(m_ui->catPackComboBox, &QComboBox::currentIndexChanged, this, &AppearanceWidget::applyCatTheme);
    connect(m_ui->catOpacitySlider, &QAbstractSlider::valueChanged, this, &AppearanceWidget::updateCatPreview);

    connect(m_ui->iconsFolder, &QPushButton::clicked, this,
            [] { DesktopServices::openPath(APPLICATION->themeManager()->getIconThemesFolder().path()); });
    connect(m_ui->widgetStyleFolder, &QPushButton::clicked, this,
            [] { DesktopServices::openPath(APPLICATION->themeManager()->getApplicationThemesFolder().path()); });
    connect(m_ui->catPackFolder, &QPushButton::clicked, this,
            [] { DesktopServices::openPath(APPLICATION->themeManager()->getCatPacksFolder().path()); });
    connect(m_ui->reloadThemesButton, &QPushButton::pressed, this, [this] {
        loadThemeSettings();
        APPLICATION->themeManager()->applyCurrentlySelectedTheme();
        updateConsolePreview();
    });

    connect(m_ui->uiFontBox, &QFontComboBox::currentFontChanged, this, [this] { savePhiSettings(); });
    connect(m_ui->uiFontSizeBox, &QSpinBox::valueChanged, this, [this] { savePhiSettings(); });
    connect(m_ui->presetBlockyButton, &QPushButton::clicked, this, [this] {
        // the bundled copy holds the defaults; read them from resources so they never drift from theme.json
        auto root = Json::requireObject(QString(":/themes/%1/theme.json").arg(s_editableTheme), "Bundled theme");
        if (!root)
            return;
        QMap<QString, QString> colors;
        const auto colorsObj = (*root)["colors"].toObject();
        for (auto it = colorsObj.begin(); it != colorsObj.end(); ++it)
            if (it->isString())
                colors[it.key()] = it->toString();
        applyPreset(colors);
    });
    connect(m_ui->presetPurpleButton, &QPushButton::clicked, this, [this] {
        applyPreset({ { "Window", "#1e1e24" },
                      { "WindowText", "#e8e6f0" },
                      { "Base", "#16161c" },
                      { "AlternateBase", "#1c1c22" },
                      { "Text", "#e8e6f0" },
                      { "Button", "#3a3a4a" },
                      { "ButtonText", "#ffffff" },
                      { "Highlight", "#6d4cff" },
                      { "HighlightedText", "#ffffff" },
                      { "Link", "#a78bfa" },
                      { "Light", "#6e6e82" },
                      { "Midlight", "#4a4a5c" },
                      { "Mid", "#7a7a8e" },
                      { "Dark", "#111116" },
                      { "PlaceholderText", "#8a8aa0" },
                      { "fadeColor", "#1e1e24" } });
    });
    connect(m_ui->presetLightButton, &QPushButton::clicked, this, [this] {
        applyPreset({ { "Window", "#f2f0f4" },
                      { "WindowText", "#1f1b2e" },
                      { "Base", "#ffffff" },
                      { "AlternateBase", "#f7f5f9" },
                      { "Text", "#1f1b2e" },
                      { "Button", "#e6e2ec" },
                      { "ButtonText", "#1f1b2e" },
                      { "Highlight", "#3a8f3a" },
                      { "HighlightedText", "#ffffff" },
                      { "Link", "#2f6f2f" },
                      { "Light", "#ffffff" },
                      { "Midlight", "#d6d2dc" },
                      { "Mid", "#8a8494" },
                      { "Dark", "#a8a2b2" },
                      { "PlaceholderText", "#8a8494" },
                      { "fadeColor", "#f2f0f4" } });
    });
    connect(m_ui->resetThemeButton, &QPushButton::clicked, this, &AppearanceWidget::resetPhiTheme);
}

void AppearanceWidget::buildColorGrid()
{
    auto* validator = new QRegularExpressionValidator(QRegularExpression("#[0-9a-fA-F]{6}"), this);
    const int rowsPerColumn = static_cast<int>((s_paletteKeys.size() + 1) / 2);
    for (int i = 0; i < static_cast<int>(s_paletteKeys.size()); ++i) {
        const QString key = s_paletteKeys[i];
        auto* label = new QLabel(key, this);
        auto* swatch = new QToolButton(this);
        swatch->setToolTip(tr("Pick a color"));
        auto* edit = new QLineEdit(this);
        edit->setValidator(validator);
        edit->setMaxLength(7);
        edit->setPlaceholderText("#rrggbb");

        const int row = i % rowsPerColumn;
        const int column = (i / rowsPerColumn) * 3;
        m_ui->colorGrid->addWidget(label, row, column);
        m_ui->colorGrid->addWidget(swatch, row, column + 1);
        m_ui->colorGrid->addWidget(edit, row, column + 2);
        m_colorRows.insert(key, { swatch, edit });

        connect(swatch, &QToolButton::clicked, this, [this, key] { pickColor(key); });
        connect(edit, &QLineEdit::textChanged, this, [swatch](const QString& text) {
            QColor color(text);
            if (color.isValid())
                setSwatchColor(swatch, color);
        });
        connect(edit, &QLineEdit::editingFinished, this, [this] { savePhiSettings(); });
    }
}

void AppearanceWidget::loadPhiSettings()
{
    auto root = Json::requireObject(themeJsonPath(), "Blocky theme");
    m_ui->phiBox->setEnabled(root.has_value());
    if (!root) {
        themeWarningLog() << "Couldn't read theme json for the Phi section:" << root.error();
        return;
    }

    m_loadingPhi = true;
    const auto fontObj = (*root)["font"].toObject();
    const QString family = fontObj["family"].toString();
    m_ui->uiFontBox->setCurrentFont(family.isEmpty() ? QApplication::font() : QFont(family));
    m_ui->uiFontSizeBox->setValue(fontObj["pointSize"].toInt(QApplication::font().pointSize()));

    const auto colors = (*root)["colors"].toObject();
    for (auto it = m_colorRows.begin(); it != m_colorRows.end(); ++it) {
        QColor color(colors[it.key()].toString());
        it->edit->setText(color.isValid() ? color.name() : QString());
        setSwatchColor(it->swatch, color.isValid() ? color : QColor(Qt::transparent));
    }
    m_loadingPhi = false;
}

void AppearanceWidget::savePhiSettings()
{
    if (m_loadingPhi)
        return;
    applyPreset({});
}

/// Writes the given colors plus everything in the grid and the font row into theme.json, then re-applies the theme.
void AppearanceWidget::applyPreset(const QMap<QString, QString>& colors)
{
    const QString path = themeJsonPath();
    auto root = Json::requireObject(path, "Blocky theme");
    if (!root) {
        themeWarningLog() << "Couldn't read theme json:" << root.error();
        return;
    }

    m_loadingPhi = true;
    for (auto it = colors.begin(); it != colors.end(); ++it) {
        if (m_colorRows.contains(it.key()))
            m_colorRows[it.key()].edit->setText(it.value());
    }
    m_loadingPhi = false;

    auto colorsObj = (*root)["colors"].toObject();
    for (auto it = colors.begin(); it != colors.end(); ++it)
        colorsObj[it.key()] = it.value();
    for (auto it = m_colorRows.begin(); it != m_colorRows.end(); ++it) {
        QColor color(it->edit->text());
        if (color.isValid())
            colorsObj[it.key()] = color.name();
    }
    (*root)["colors"] = colorsObj;
    (*root)["font"] = QJsonObject{ { "family", m_ui->uiFontBox->currentFont().family() }, { "pointSize", m_ui->uiFontSizeBox->value() } };

    if (auto res = Json::write(*root, path); !res) {
        themeWarningLog() << "Couldn't write theme json:" << res.error();
        return;
    }
    APPLICATION->themeManager()->reloadTheme(s_editableTheme);
    updateConsolePreview();
}

void AppearanceWidget::resetPhiTheme()
{
    APPLICATION->themeManager()->resetBundledTheme(s_editableTheme);
    loadPhiSettings();
    updateConsolePreview();
}

void AppearanceWidget::pickColor(const QString& key)
{
    auto& row = m_colorRows[key];
    auto color = QColorDialog::getColor(QColor(row.edit->text()), this, tr("Color for %1").arg(key));
    if (!color.isValid())
        return;
    row.edit->setText(color.name());
    savePhiSettings();
}

AppearanceWidget::~AppearanceWidget()
{
    delete m_ui;
}

void AppearanceWidget::applySettings()
{
    SettingsObject* settings = APPLICATION->settings();
    QString consoleFontFamily = m_ui->consoleFont->currentFont().family();
    settings->set("ConsoleFont", consoleFontFamily);
    settings->set("ConsoleFontSize", m_ui->fontSizeBox->value());
    const bool catEnabled = m_ui->enableCatCheckBox->isChecked();
    settings->set("EnableCat", catEnabled);
    if (!catEnabled) {
        settings->set("TheCat", false);
    }
    settings->set("CatOpacity", m_ui->catOpacitySlider->value());
    auto catFit = m_ui->catFitComboBox->currentIndex();
    settings->set("CatFit", catFit == 0 ? "fit" : catFit == 1 ? "fill" : "strech");
}

void AppearanceWidget::loadSettings()
{
    SettingsObject* settings = APPLICATION->settings();
    QString fontFamily = settings->get("ConsoleFont").toString();
    QFont consoleFont(fontFamily);
    m_ui->consoleFont->setCurrentFont(consoleFont);

    bool conversionOk = true;
    int fontSize = settings->get("ConsoleFontSize").toInt(&conversionOk);
    if (!conversionOk) {
        fontSize = 11;
    }
    m_ui->fontSizeBox->setValue(fontSize);

    m_ui->enableCatCheckBox->setChecked(settings->get("EnableCat").toBool());
    m_ui->catOpacitySlider->setValue(settings->get("CatOpacity").toInt());

    auto catFit = settings->get("CatFit").toString();
    m_ui->catFitComboBox->setCurrentIndex(catFit == "fit" ? 0 : catFit == "fill" ? 1 : 2);
}

void AppearanceWidget::retranslateUi()
{
    m_ui->retranslateUi(this);
}

void AppearanceWidget::applyIconTheme(int index)
{
    auto settings = APPLICATION->settings();
    auto originalIconTheme = settings->get("IconTheme").toString();
    auto newIconTheme = m_ui->iconsComboBox->itemData(index).toString();
    if (originalIconTheme != newIconTheme) {
        settings->set("IconTheme", newIconTheme);
        APPLICATION->themeManager()->applyCurrentlySelectedTheme();
    }
}

void AppearanceWidget::applyWidgetTheme(int index)
{
    auto settings = APPLICATION->settings();
    auto originalAppTheme = settings->get("ApplicationTheme").toString();
    auto newAppTheme = m_ui->widgetStyleComboBox->itemData(index).toString();
    if (originalAppTheme != newAppTheme) {
        settings->set("ApplicationTheme", newAppTheme);
        APPLICATION->themeManager()->applyCurrentlySelectedTheme();
    }

    updateConsolePreview();
}

void AppearanceWidget::applyCatTheme(int index)
{
    auto settings = APPLICATION->settings();
    auto originalCat = settings->get("BackgroundCat").toString();
    auto newCat = m_ui->catPackComboBox->itemData(index).toString();
    if (originalCat != newCat) {
        settings->set("BackgroundCat", newCat);
    }

    APPLICATION->currentCatChanged(index);
    updateCatPreview();
}

void AppearanceWidget::loadThemeSettings()
{
    APPLICATION->themeManager()->refresh();

    m_ui->iconsComboBox->blockSignals(true);
    m_ui->widgetStyleComboBox->blockSignals(true);
    m_ui->catPackComboBox->blockSignals(true);

    m_ui->iconsComboBox->clear();
    m_ui->widgetStyleComboBox->clear();
    m_ui->catPackComboBox->clear();

    SettingsObject* settings = APPLICATION->settings();

    const QString currentIconTheme = settings->get("IconTheme").toString();
    const auto iconThemes = APPLICATION->themeManager()->getValidIconThemes();

    for (int i = 0; i < iconThemes.count(); ++i) {
        const IconTheme* theme = iconThemes[i];

        QIcon iconForComboBox = QIcon(theme->path() + "/scalable/settings");
        m_ui->iconsComboBox->addItem(iconForComboBox, theme->name(), theme->id());

        if (currentIconTheme == theme->id())
            m_ui->iconsComboBox->setCurrentIndex(i);
    }

    const QString currentTheme = settings->get("ApplicationTheme").toString();
    auto themes = APPLICATION->themeManager()->getValidApplicationThemes();
    for (int i = 0; i < themes.count(); ++i) {
        ITheme* theme = themes[i];

        m_ui->widgetStyleComboBox->addItem(theme->name(), theme->id());

        if (!theme->tooltip().isEmpty())
            m_ui->widgetStyleComboBox->setItemData(i, theme->tooltip(), Qt::ToolTipRole);

        if (currentTheme == theme->id())
            m_ui->widgetStyleComboBox->setCurrentIndex(i);
    }

    if (!m_themesOnly) {
        const QString currentCat = settings->get("BackgroundCat").toString();
        const auto cats = APPLICATION->themeManager()->getValidCatPacks();
        for (int i = 0; i < cats.count(); ++i) {
            const CatPack* cat = cats[i];

            QIcon catIcon = QIcon(QString("%1").arg(cat->path()));
            m_ui->catPackComboBox->addItem(catIcon, cat->name(), cat->id());

            if (currentCat == cat->id())
                m_ui->catPackComboBox->setCurrentIndex(i);
        }
    }

    m_ui->iconsComboBox->blockSignals(false);
    m_ui->widgetStyleComboBox->blockSignals(false);
    m_ui->catPackComboBox->blockSignals(false);

    if (!m_themesOnly)
        loadPhiSettings();
}

void AppearanceWidget::updateConsolePreview()
{
    const LogColors& colors = APPLICATION->themeManager()->getLogColors();

    int fontSize = m_ui->fontSizeBox->value();
    QString fontFamily = m_ui->consoleFont->currentFont().family();
    m_ui->consolePreview->clear();
    m_defaultFormat.setFont(QFont(fontFamily, fontSize));

    auto print = [this, colors](const QString& message, MessageLevel level) {
        QTextCharFormat format(m_defaultFormat);

        QColor bg = colors.background.value(level);
        QColor fg = colors.foreground.value(level);

        if (bg.isValid())
            format.setBackground(bg);

        if (fg.isValid())
            format.setForeground(fg);

        // append a paragraph/line
        auto workCursor = m_ui->consolePreview->textCursor();
        workCursor.movePosition(QTextCursor::End);
        workCursor.insertText(message, format);
        workCursor.insertBlock();
    };

    print(QString("%1 version: %2\n").arg(BuildConfig.LAUNCHER_DISPLAYNAME, BuildConfig.printableVersionString()), MessageLevel::Launcher);

    QDate today = QDate::currentDate();

    if (today.month() == 10 && today.day() == 31)
        print(tr("[ERROR] OOoooOOOoooo! A spooky error!"), MessageLevel::Error);
    else
        print(tr("[ERROR] A spooky error!"), MessageLevel::Error);

    print(tr("[INFO] A harmless message..."), MessageLevel::Info);
    print(tr("[WARN] A not so spooky warning."), MessageLevel::Warning);
    print(tr("[DEBUG] A secret debugging message..."), MessageLevel::Debug);
    print(tr("[FATAL] A terrifying fatal error!"), MessageLevel::Fatal);
}

void AppearanceWidget::updateCatPreview()
{
    QIcon catPackIcon(APPLICATION->themeManager()->getCatPack());
    m_ui->catPreview->setIcon(catPackIcon);

    auto effect = dynamic_cast<QGraphicsOpacityEffect*>(m_ui->catPreview->graphicsEffect());
    if (effect)
        effect->setOpacity(m_ui->catOpacitySlider->value() / 100.0);
}
