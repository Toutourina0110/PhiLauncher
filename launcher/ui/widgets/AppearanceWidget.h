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
 */

#pragma once

#include <QDialog>

#include <translations/TranslationsModel.h>
#include <QMap>
#include <QTextCursor>

class QLineEdit;
class QTextCharFormat;
class QToolButton;
class SettingsObject;

namespace Ui {
class AppearanceWidget;
}

class AppearanceWidget : public QWidget {
    Q_OBJECT

   public:
    explicit AppearanceWidget(bool simple, QWidget* parent = 0);
    virtual ~AppearanceWidget();

   public:
    void applySettings();
    void loadSettings();
    void retranslateUi();

   private:
    void applyIconTheme(int index);
    void applyWidgetTheme(int index);
    void applyCatTheme(int index);
    void loadThemeSettings();

    void updateConsolePreview();
    void updateCatPreview();

    // "Phi" section: edits themes/blocky/theme.json and re-applies it live
    void buildColorGrid();
    void loadPhiSettings();
    void savePhiSettings();
    void applyPreset(const QMap<QString, QString>& colors);
    void resetPhiTheme();
    void pickColor(const QString& key);

    struct ColorRow {
        QToolButton* swatch;
        QLineEdit* edit;
    };

    Ui::AppearanceWidget* m_ui;
    QTextCharFormat m_defaultFormat;
    bool m_themesOnly;
    QMap<QString, ColorRow> m_colorRows;
    bool m_loadingPhi = true;  // stays true until loadPhiSettings() ran once, so no stray save overwrites theme.json
};
