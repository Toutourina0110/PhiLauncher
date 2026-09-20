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

#include "StatsCharts.h"

#include <algorithm>
#include <cmath>

#include <QFontMetrics>
#include <QLocale>
#include <QHelpEvent>
#include <QPainter>
#include <QToolTip>

#include "MMCTime.h"

namespace {
const int kPad = 8;

QString fmt(qint64 secs)
{
    return secs > 0 ? Time::prettifyDuration(secs) : QStringLiteral("0s");
}

QColor barColor(const QPalette& pal)
{
    return pal.color(QPalette::Highlight);
}

QColor trackColor(const QPalette& pal)
{
    QColor c = pal.color(QPalette::Text);
    c.setAlpha(24);
    return c;
}
}  // namespace

// ---------------------------------------------------------------- BarChart

BarChart::BarChart(QWidget* parent) : QWidget(parent)
{
    setMinimumHeight(180);
    setMouseTracking(true);
}

void BarChart::setData(const QList<QPair<QString, qint64>>& values)
{
    m_data = values;
    m_max = 0;
    for (const auto& d : m_data)
        m_max = std::max(m_max, d.second);
    update();
}

QRect BarChart::plotRect() const
{
    int labelH = fontMetrics().height() + 4;
    return rect().adjusted(kPad, kPad, -kPad, -kPad - labelH);
}

int BarChart::barAt(const QPoint& p) const
{
    if (m_data.isEmpty())
        return -1;
    QRect r = plotRect();
    if (!r.contains(p.x(), p.y()) && !(p.y() > r.bottom() && p.y() < height()))
        return -1;
    int i = (p.x() - r.left()) * m_data.size() / std::max(1, r.width());
    return (i >= 0 && i < m_data.size()) ? i : -1;
}

void BarChart::paintEvent(QPaintEvent*)
{
    QPainter g(this);
    g.setRenderHint(QPainter::Antialiasing);
    const QPalette pal = palette();
    QRect r = plotRect();

    if (m_data.isEmpty()) {
        g.setPen(pal.color(QPalette::PlaceholderText));
        g.drawText(rect(), Qt::AlignCenter, tr("No data yet"));
        return;
    }

    const int n = m_data.size();
    const double slot = double(r.width()) / n;
    const int gap = slot > 6 ? 2 : 0;
    QFontMetrics fm = fontMetrics();
    // label every k-th bar counted from the newest one so labels never overlap and the last bar is always labelled
    int labelEvery = std::max(1, int(std::ceil((fm.horizontalAdvance("00/00") + 6) / slot)));
    const int labelW = fm.horizontalAdvance("00/00") + 4;

    for (int i = 0; i < n; i++) {
        int x0 = r.left() + int(i * slot);
        int x1 = r.left() + int((i + 1) * slot);
        QRect track(x0 + gap, r.top(), std::max(1, x1 - x0 - 2 * gap), r.height());
        g.fillRect(track, trackColor(pal));
        if (m_max > 0 && m_data[i].second > 0) {
            int h = std::max(2, int(double(track.height()) * m_data[i].second / m_max));
            g.fillRect(QRect(track.left(), track.bottom() - h + 1, track.width(), h), barColor(pal));
        }
        if ((n - 1 - i) % labelEvery == 0) {
            int cx = (x0 + x1) / 2;
            int lx = std::clamp(cx - labelW / 2, r.left(), r.right() - labelW);
            g.setPen(pal.color(QPalette::Text));
            g.drawText(QRect(lx, r.bottom() + 2, labelW, fm.height() + 2), Qt::AlignHCenter | Qt::AlignTop, m_data[i].first);
        }
    }
}

bool BarChart::event(QEvent* e)
{
    if (e->type() == QEvent::ToolTip) {
        auto* he = static_cast<QHelpEvent*>(e);
        int i = barAt(he->pos());
        if (i >= 0)
            QToolTip::showText(he->globalPos(), QString("%1: %2").arg(m_data[i].first, fmt(m_data[i].second)), this);
        else
            QToolTip::hideText();
        return true;
    }
    return QWidget::event(e);
}

// ---------------------------------------------------------------- HBarChart

HBarChart::HBarChart(QWidget* parent) : QWidget(parent)
{
    setMouseTracking(true);
}

void HBarChart::setData(const QList<QPair<QString, qint64>>& values)
{
    m_data = values;
    m_max = 0;
    m_labelWidth = 0;
    QFontMetrics fm = fontMetrics();
    for (const auto& d : m_data) {
        m_max = std::max(m_max, d.second);
        m_labelWidth = std::max(m_labelWidth, fm.horizontalAdvance(d.first));
    }
    m_labelWidth = std::min(m_labelWidth, 160);
    const int rowH = fm.height() + 8;
    setMinimumHeight(std::max(60, int(m_data.size()) * rowH + 2 * kPad));
    update();
}

int HBarChart::barAt(const QPoint& p) const
{
    if (m_data.isEmpty())
        return -1;
    const int rowH = fontMetrics().height() + 8;
    int i = (p.y() - kPad) / rowH;
    return (p.y() >= kPad && i >= 0 && i < m_data.size()) ? i : -1;
}

void HBarChart::paintEvent(QPaintEvent*)
{
    QPainter g(this);
    g.setRenderHint(QPainter::Antialiasing);
    const QPalette pal = palette();

    if (m_data.isEmpty()) {
        g.setPen(pal.color(QPalette::PlaceholderText));
        g.drawText(rect(), Qt::AlignCenter, tr("No data yet"));
        return;
    }

    QFontMetrics fm = fontMetrics();
    const int rowH = fm.height() + 8;
    const int barX = kPad + m_labelWidth + kPad;
    const int valueW = fm.horizontalAdvance("00d 00h 00m") + kPad;
    const int barW = std::max(10, width() - barX - valueW - kPad);

    for (int i = 0; i < m_data.size(); i++) {
        int y = kPad + i * rowH;
        QRect labelR(kPad, y, m_labelWidth, rowH);
        g.setPen(pal.color(QPalette::Text));
        g.drawText(labelR, Qt::AlignVCenter | Qt::AlignLeft, fm.elidedText(m_data[i].first, Qt::ElideRight, m_labelWidth));

        QRect track(barX, y + 3, barW, rowH - 6);
        g.fillRect(track, trackColor(pal));
        if (m_max > 0) {
            int w = std::max(2, int(double(barW) * m_data[i].second / m_max));
            g.fillRect(QRect(track.left(), track.top(), w, track.height()), barColor(pal));
        }
        g.drawText(QRect(barX + barW + kPad, y, valueW, rowH), Qt::AlignVCenter | Qt::AlignLeft, fmt(m_data[i].second));
    }
}

bool HBarChart::event(QEvent* e)
{
    if (e->type() == QEvent::ToolTip) {
        auto* he = static_cast<QHelpEvent*>(e);
        int i = barAt(he->pos());
        if (i >= 0)
            QToolTip::showText(he->globalPos(), QString("%1: %2").arg(m_data[i].first, fmt(m_data[i].second)), this);
        else
            QToolTip::hideText();
        return true;
    }
    return QWidget::event(e);
}

// ---------------------------------------------------------------- HeatmapChart

namespace {
const int kDayLabelW = 34;
const int kHourLabelH = 16;
QString dayName(int d)
{
    return QLocale().dayName(d + 1, QLocale::ShortFormat);
}
}  // namespace

HeatmapChart::HeatmapChart(QWidget* parent) : QWidget(parent)
{
    setMinimumHeight(7 * 18 + kHourLabelH + 2 * kPad);
    setMouseTracking(true);
}

void HeatmapChart::setData(const QVector<QVector<qint64>>& grid)
{
    m_grid = grid;
    m_max = 0;
    for (const auto& row : m_grid)
        for (qint64 v : row)
            m_max = std::max(m_max, v);
    update();
}

QRect HeatmapChart::gridRect() const
{
    return rect().adjusted(kPad + kDayLabelW, kPad, -kPad, -kPad - kHourLabelH);
}

QRect HeatmapChart::cellRect(int day, int hour) const
{
    QRect g = gridRect();
    double cw = double(g.width()) / 24, ch = double(g.height()) / 7;
    return QRect(g.left() + int(hour * cw), g.top() + int(day * ch), int(cw), int(ch)).adjusted(1, 1, -1, -1);
}

void HeatmapChart::paintEvent(QPaintEvent*)
{
    QPainter g(this);
    const QPalette pal = palette();
    QFontMetrics fm = fontMetrics();
    QRect gr = gridRect();

    g.setPen(pal.color(QPalette::Text));
    for (int d = 0; d < 7; d++) {
        QRect c = cellRect(d, 0);
        g.drawText(QRect(kPad, c.top(), kDayLabelW - 4, c.height()), Qt::AlignVCenter | Qt::AlignLeft, dayName(d));
    }
    for (int h = 0; h < 24; h += 3) {
        QRect c = cellRect(0, h);
        g.drawText(QRect(c.left(), gr.bottom() + 2, c.width() * 3, kHourLabelH), Qt::AlignLeft | Qt::AlignTop, QString::number(h) + "h");
    }

    QColor hi = barColor(pal);
    for (int d = 0; d < 7; d++) {
        for (int h = 0; h < 24; h++) {
            qint64 v = (d < m_grid.size() && h < m_grid[d].size()) ? m_grid[d][h] : 0;
            QColor c = trackColor(pal);
            if (m_max > 0 && v > 0) {
                c = hi;
                c.setAlphaF(0.25 + 0.75 * double(v) / m_max);
            }
            g.fillRect(cellRect(d, h), c);
        }
    }
}

bool HeatmapChart::event(QEvent* e)
{
    if (e->type() == QEvent::ToolTip) {
        auto* he = static_cast<QHelpEvent*>(e);
        QRect gr = gridRect();
        if (gr.contains(he->pos()) && !m_grid.isEmpty()) {
            int h = (he->pos().x() - gr.left()) * 24 / std::max(1, gr.width());
            int d = (he->pos().y() - gr.top()) * 7 / std::max(1, gr.height());
            h = std::clamp(h, 0, 23);
            d = std::clamp(d, 0, 6);
            QToolTip::showText(he->globalPos(), QString("%1 %2h: %3").arg(dayName(d), QString::number(h), fmt(m_grid[d][h])), this);
        } else {
            QToolTip::hideText();
        }
        return true;
    }
    return QWidget::event(e);
}
