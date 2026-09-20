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

#include "ActivityPanel.h"

#include <QFrame>
#include <QHBoxLayout>
#include <QLabel>
#include <QToolButton>
#include <QLocale>
#include <QVBoxLayout>

#include "Application.h"
#include "MMCTime.h"
#include "StatsStore.h"
#include "settings/SettingsObject.h"
#include "ui/widgets/StatsCharts.h"

ActivityPanel::ActivityPanel(QWidget* parent) : QWidget(parent)
{
    setObjectName(QStringLiteral("activityPanel"));
    setFixedWidth(240);

    auto* layout = new QVBoxLayout(this);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(6);

    auto* titleRow = new QHBoxLayout();
    titleRow->setContentsMargins(0, 0, 0, 0);
    auto* title = new QLabel(tr("Activity"), this);
    title->setForegroundRole(QPalette::Mid);
    titleRow->addWidget(title);
    titleRow->addStretch();
    auto* refreshButton = new QToolButton(this);
    refreshButton->setIcon(QIcon::fromTheme("refresh"));
    refreshButton->setToolTip(tr("Refresh statistics"));
    refreshButton->setAutoRaise(true);
    connect(refreshButton, &QToolButton::clicked, APPLICATION->stats(), &StatsStore::refreshNow);
    titleRow->addWidget(refreshButton);
    layout->addLayout(titleRow);

    auto* box = new QFrame(this);
    box->setFrameShape(QFrame::StyledPanel);
    layout->addWidget(box, 1);
    auto* boxLayout = new QVBoxLayout(box);
    boxLayout->setContentsMargins(10, 10, 10, 10);
    boxLayout->setSpacing(8);

    m_total = tile(tr("Total playtime"), box);
    m_launches = tile(tr("Launches"), box);
    m_week = tile(tr("This week"), box);
    boxLayout->addWidget(m_total->parentWidget());
    boxLayout->addWidget(m_launches->parentWidget());
    boxLayout->addWidget(m_week->parentWidget());

    auto* chartBox = new QFrame(box);
    chartBox->setFrameShape(QFrame::StyledPanel);
    auto* chartLayout = new QVBoxLayout(chartBox);
    chartLayout->setContentsMargins(8, 8, 8, 4);
    chartLayout->setSpacing(4);
    auto* chartTitle = new QLabel(tr("Last 7 days"), chartBox);
    chartTitle->setForegroundRole(QPalette::Mid);
    chartLayout->addWidget(chartTitle);
    m_chart = new BarChart(chartBox);
    m_chart->setMinimumHeight(110);
    chartLayout->addWidget(m_chart);
    boxLayout->addWidget(chartBox);
    boxLayout->addStretch(1);

    connect(APPLICATION->stats(), &StatsStore::changed, this, &ActivityPanel::refresh);
    refresh();
}

QLabel* ActivityPanel::tile(const QString& caption, QWidget* parent)
{
    auto* frame = new QFrame(parent);
    frame->setFrameShape(QFrame::StyledPanel);
    auto* layout = new QVBoxLayout(frame);
    layout->setContentsMargins(10, 8, 10, 8);
    layout->setSpacing(2);
    auto* captionLabel = new QLabel(caption, frame);
    captionLabel->setForegroundRole(QPalette::Mid);
    layout->addWidget(captionLabel);
    auto* value = new QLabel(frame);
    QFont f = value->font();
    f.setPointSize(f.pointSize() + 4);
    f.setBold(true);
    value->setFont(f);
    layout->addWidget(value);
    return value;
}

void ActivityPanel::refresh()
{
    auto* stats = APPLICATION->stats();
    const bool noDays = APPLICATION->settings()->get("ShowGameTimeWithoutDays").toBool();
    m_total->setText(Time::prettifyDuration(stats->totalSeconds(), noDays));
    m_launches->setText(QString::number(stats->launchCount()));

    qint64 week = 0;
    QList<QPair<QString, qint64>> days;
    for (const auto& d : stats->secondsPerDay(7)) {
        week += d.second;
        days.append({ QLocale().dayName(d.first.dayOfWeek(), QLocale::NarrowFormat), d.second });
    }
    m_week->setText(Time::prettifyDuration(week, noDays));
    m_chart->setData(days);
}
