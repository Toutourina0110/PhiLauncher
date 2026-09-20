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

#include "StatsDialog.h"

#include <algorithm>

#include <QComboBox>
#include <QDateTime>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QHeaderView>
#include <QLabel>
#include <QLocale>
#include <QScrollArea>
#include <QTableWidget>
#include <QVBoxLayout>

#include "MMCTime.h"
#include "StatsStore.h"
#include "ui/widgets/StatsCharts.h"

namespace {
QString dur(qint64 secs)
{
    return secs > 0 ? Time::prettifyDuration(secs) : QStringLiteral("0s");
}
}  // namespace

StatsDialog::StatsDialog(StatsStore* store, QWidget* parent) : QDialog(parent), m_store(store)
{
    setWindowTitle(tr("Statistics"));
    resize(820, 720);

    auto* scroll = new QScrollArea(this);
    scroll->setWidgetResizable(true);
    scroll->setFrameShape(QFrame::NoFrame);
    auto* body = new QWidget(scroll);
    scroll->setWidget(body);
    auto* outer = new QVBoxLayout(this);
    outer->setContentsMargins(0, 0, 0, 0);
    outer->addWidget(scroll);

    auto* layout = new QVBoxLayout(body);

    // summary tiles
    auto* tiles = new QHBoxLayout();
    m_total = tile(tr("Total playtime"), body);
    m_launches = tile(tr("Launches"), body);
    m_top = tile(tr("Most played"), body);
    m_average = tile(tr("Average session"), body);
    m_longest = tile(tr("Longest session"), body);
    for (auto* t : { m_total, m_launches, m_top, m_average, m_longest })
        tiles->addWidget(t->parentWidget());
    layout->addLayout(tiles);

    // per-day chart with period selector
    auto* dayBox = new QGroupBox(tr("Playtime per day"), body);
    auto* dayLayout = new QVBoxLayout(dayBox);
    auto* periodRow = new QHBoxLayout();
    m_period = new QComboBox(dayBox);
    m_period->addItem(tr("Last 7 days"), 7);
    m_period->addItem(tr("Last 30 days"), 30);
    m_period->addItem(tr("Last 90 days"), 90);
    m_period->addItem(tr("All time"), 0);
    m_period->setCurrentIndex(1);
    periodRow->addStretch();
    periodRow->addWidget(m_period);
    dayLayout->addLayout(periodRow);
    m_perDay = new BarChart(dayBox);
    dayLayout->addWidget(m_perDay);
    layout->addWidget(dayBox);

    // top instances + heatmap side by side
    auto* row = new QHBoxLayout();
    auto* instBox = new QGroupBox(tr("Top instances"), body);
    m_perInstance = new HBarChart(instBox);
    auto* instLayout = new QVBoxLayout(instBox);
    instLayout->addWidget(m_perInstance);
    instLayout->addStretch();
    row->addWidget(instBox, 1);

    auto* heatBox = new QGroupBox(tr("When you play"), body);
    m_heatmap = new HeatmapChart(heatBox);
    auto* heatLayout = new QVBoxLayout(heatBox);
    heatLayout->addWidget(m_heatmap);
    row->addWidget(heatBox, 1);
    layout->addLayout(row);

    // recent sessions
    auto* sessBox = new QGroupBox(tr("Recent sessions"), body);
    m_sessions = new QTableWidget(0, 3, sessBox);
    m_sessions->setHorizontalHeaderLabels({ tr("Date"), tr("Instance"), tr("Duration") });
    m_sessions->horizontalHeader()->setSectionResizeMode(1, QHeaderView::Stretch);
    m_sessions->verticalHeader()->hide();
    m_sessions->setEditTriggers(QAbstractItemView::NoEditTriggers);
    m_sessions->setSelectionMode(QAbstractItemView::NoSelection);
    m_sessions->setMinimumHeight(220);
    auto* sessLayout = new QVBoxLayout(sessBox);
    sessLayout->addWidget(m_sessions);
    layout->addWidget(sessBox);

    connect(m_period, &QComboBox::currentIndexChanged, this, &StatsDialog::refresh);
    connect(m_store, &StatsStore::changed, this, &StatsDialog::refresh);
    refresh();
}

QLabel* StatsDialog::tile(const QString& title, QWidget* parent)
{
    auto* box = new QGroupBox(title, parent);
    auto* value = new QLabel(box);
    QFont f = value->font();
    f.setPointSize(f.pointSize() + 4);
    f.setBold(true);
    value->setFont(f);
    value->setAlignment(Qt::AlignCenter);
    value->setWordWrap(true);
    auto* l = new QVBoxLayout(box);
    l->addWidget(value);
    return value;
}

void StatsDialog::refresh()
{
    m_total->setText(dur(m_store->totalSeconds()));
    m_launches->setText(QString::number(m_store->launchCount()));
    m_average->setText(dur(m_store->averageSeconds()));
    m_longest->setText(dur(m_store->longestSeconds()));

    auto perInst = m_store->secondsPerInstance();
    m_top->setText(perInst.isEmpty() ? QStringLiteral("-") : perInst.first().first);
    if (perInst.size() > 10)
        perInst = perInst.mid(0, 10);
    m_perInstance->setData(perInst);

    QList<QPair<QString, qint64>> days;
    for (const auto& d : m_store->secondsPerDay(m_period->currentData().toInt()))
        days.append({ QLocale().toString(d.first, QStringLiteral("dd/MM")), d.second });
    m_perDay->setData(days);

    m_heatmap->setData(m_store->heatmap());

    auto all = m_store->sessions();
    std::sort(all.begin(), all.end(), [](const GameSession& a, const GameSession& b) { return a.start > b.start; });
    m_sessions->setRowCount(0);
    for (const auto& s : all) {
        if (s.start <= 0 || m_sessions->rowCount() >= 50)
            break;
        int r = m_sessions->rowCount();
        m_sessions->insertRow(r);
        m_sessions->setItem(r, 0, new QTableWidgetItem(QLocale().toString(QDateTime::fromMSecsSinceEpoch(s.start), QLocale::ShortFormat)));
        m_sessions->setItem(r, 1, new QTableWidgetItem(s.instanceName.isEmpty() ? s.instanceId : s.instanceName));
        m_sessions->setItem(r, 2, new QTableWidgetItem(dur(s.duration)));
    }
}
