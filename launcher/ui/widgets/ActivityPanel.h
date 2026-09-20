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

class BarChart;
class QLabel;

/// Right-hand column: playtime tiles and a "last 7 days" bar chart fed by the StatsStore.
class ActivityPanel : public QWidget {
    Q_OBJECT

   public:
    explicit ActivityPanel(QWidget* parent = nullptr);

   private:
    QLabel* tile(const QString& caption, QWidget* parent);
    void refresh();

    QLabel* m_total = nullptr;
    QLabel* m_launches = nullptr;
    QLabel* m_week = nullptr;
    BarChart* m_chart = nullptr;
};
