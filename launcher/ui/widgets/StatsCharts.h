// SPDX-License-Identifier: GPL-3.0-only
/*
 *  Iota Launcher - Minecraft Launcher
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

#include <QList>
#include <QPair>
#include <QString>
#include <QVector>
#include <QWidget>

/// Minimal QPainter charts that follow the widget palette, so every Prism theme works.
/// Values are seconds; tooltips show them as durations.

class BarChart : public QWidget {
    Q_OBJECT
   public:
    explicit BarChart(QWidget* parent = nullptr);
    void setData(const QList<QPair<QString, qint64>>& data);

   protected:
    void paintEvent(QPaintEvent*) override;
    bool event(QEvent* e) override;

   private:
    int barAt(const QPoint& p) const;
    QRect plotRect() const;

    QList<QPair<QString, qint64>> m_data;
    qint64 m_max = 0;
};

class HBarChart : public QWidget {
    Q_OBJECT
   public:
    explicit HBarChart(QWidget* parent = nullptr);
    void setData(const QList<QPair<QString, qint64>>& data);

   protected:
    void paintEvent(QPaintEvent*) override;
    bool event(QEvent* e) override;

   private:
    int barAt(const QPoint& p) const;

    QList<QPair<QString, qint64>> m_data;
    qint64 m_max = 0;
    int m_labelWidth = 0;
};

class HeatmapChart : public QWidget {
    Q_OBJECT
   public:
    explicit HeatmapChart(QWidget* parent = nullptr);
    /// grid[day 0=Mon..6][hour 0..23]
    void setData(const QVector<QVector<qint64>>& grid);

   protected:
    void paintEvent(QPaintEvent*) override;
    bool event(QEvent* e) override;

   private:
    QRect cellRect(int day, int hour) const;
    QRect gridRect() const;

    QVector<QVector<qint64>> m_grid;
    qint64 m_max = 0;
};
