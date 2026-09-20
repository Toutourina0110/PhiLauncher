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

#include <QDialog>

class QLabel;
class QComboBox;
class QTableWidget;
class BarChart;
class HBarChart;
class HeatmapChart;
class StatsStore;

class StatsDialog : public QDialog {
    Q_OBJECT
   public:
    explicit StatsDialog(StatsStore* store, QWidget* parent = nullptr);

   private slots:
    void refresh();

   private:
    QLabel* tile(const QString& title, QWidget* parent);

    StatsStore* m_store;
    QComboBox* m_period;
    QLabel* m_total;
    QLabel* m_launches;
    QLabel* m_top;
    QLabel* m_average;
    QLabel* m_longest;
    BarChart* m_perDay;
    HBarChart* m_perInstance;
    HeatmapChart* m_heatmap;
    QTableWidget* m_sessions;
};
