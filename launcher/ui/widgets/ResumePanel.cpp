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

#include "ResumePanel.h"

#include <QDateTime>
#include <QHBoxLayout>
#include <QIcon>
#include <QLabel>
#include <QPushButton>
#include <QToolButton>
#include <QVBoxLayout>

#include "Application.h"
#include "MMCTime.h"
#include "StatsStore.h"
#include "icons/IconList.h"
#include "minecraft/MinecraftInstance.h"
#include "ui/instanceview/InstanceCardDelegate.h"

namespace {
constexpr int kIconSize = 96;
const QString kMiddleDot = QString(QChar(0xB7));

QToolButton* flatButton(const QString& icon, const QString& text, QWidget* parent)
{
    auto* b = new QToolButton(parent);
    b->setAutoRaise(true);
    b->setIcon(QIcon::fromTheme(icon));
    b->setText(text);
    b->setToolButtonStyle(Qt::ToolButtonTextBesideIcon);
    b->setFocusPolicy(Qt::TabFocus);
    return b;
}
}  // namespace

ResumePanel::ResumePanel(QWidget* parent) : QFrame(parent)
{
    setObjectName(QStringLiteral("resumePanel"));
    setFrameShape(QFrame::StyledPanel);
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);

    auto* outer = new QVBoxLayout(this);
    outer->setContentsMargins(12, 12, 12, 12);

    m_empty = new QLabel(tr("No instance yet, add one"), this);
    m_empty->setAlignment(Qt::AlignCenter);
    m_empty->setMinimumHeight(kIconSize);
    outer->addWidget(m_empty);

    m_content = new QWidget(this);
    outer->addWidget(m_content);
    auto* row = new QHBoxLayout(m_content);
    row->setContentsMargins(0, 0, 0, 0);
    row->setSpacing(16);

    m_icon = new QLabel(m_content);
    m_icon->setFixedSize(kIconSize, kIconSize);
    m_icon->setAlignment(Qt::AlignCenter);
    row->addWidget(m_icon, 0, Qt::AlignTop);

    auto* info = new QVBoxLayout();
    info->setSpacing(2);
    row->addLayout(info, 1);

    m_name = new QLabel(m_content);
    QFont nameFont = m_name->font();
    nameFont.setBold(true);
    nameFont.setPointSize(nameFont.pointSize() + 4);
    m_name->setFont(nameFont);
    info->addWidget(m_name);

    m_version = new QLabel(m_content);
    m_version->setForegroundRole(QPalette::Mid);
    info->addWidget(m_version);

    m_played = new QLabel(m_content);
    m_played->setForegroundRole(QPalette::Mid);
    info->addWidget(m_played);

    auto* more = new QHBoxLayout();
    more->setSpacing(4);
    m_kill = flatButton("status-bad", tr("Kill"), m_content);
    m_kill->setEnabled(false);
    connect(m_kill, &QToolButton::clicked, this, &ResumePanel::killRequested);
    more->addWidget(m_kill);
    auto* groupButton = flatButton("tag", tr("Change group"), m_content);
    connect(groupButton, &QToolButton::clicked, this, &ResumePanel::changeGroupRequested);
    more->addWidget(groupButton);
    auto* exportButton = flatButton("export", tr("Export"), m_content);
    connect(exportButton, &QToolButton::clicked, this, &ResumePanel::exportRequested);
    more->addWidget(exportButton);
    auto* copyButton = flatButton("copy", tr("Copy"), m_content);
    connect(copyButton, &QToolButton::clicked, this, &ResumePanel::copyRequested);
    more->addWidget(copyButton);
    auto* deleteButton = flatButton("delete", tr("Delete"), m_content);
    connect(deleteButton, &QToolButton::clicked, this, &ResumePanel::deleteRequested);
    more->addWidget(deleteButton);
    auto* shortcutButton = flatButton("shortcut", tr("Create shortcut"), m_content);
    connect(shortcutButton, &QToolButton::clicked, this, &ResumePanel::shortcutRequested);
    more->addWidget(shortcutButton);
    more->addStretch(1);
    info->addSpacing(6);
    info->addLayout(more);

    auto* actions = new QHBoxLayout();
    actions->setSpacing(8);
    row->addLayout(actions);

    auto* editButton = new QPushButton(QIcon::fromTheme("settings"), tr("Edit"), m_content);
    connect(editButton, &QPushButton::clicked, this, &ResumePanel::editRequested);
    actions->addWidget(editButton);
    auto* folderButton = new QPushButton(QIcon::fromTheme("viewfolder"), tr("Folder"), m_content);
    connect(folderButton, &QPushButton::clicked, this, &ResumePanel::folderRequested);
    actions->addWidget(folderButton);

    m_launch = new QPushButton(QIcon::fromTheme("launch"), tr("Launch"), m_content);
    m_launch->setObjectName(QStringLiteral("launchButton"));
    QFont launchFont = m_launch->font();
    launchFont.setPointSize(launchFont.pointSize() + 4);
    m_launch->setFont(launchFont);
    m_launch->setMinimumSize(160, 48);
    connect(m_launch, &QPushButton::clicked, this, &ResumePanel::launchRequested);
    actions->addWidget(m_launch);

    setInstance(nullptr);
}

void ResumePanel::setInstance(MinecraftInstance* newInstance)
{
    if (m_instance) {
        disconnect(m_instance, nullptr, this, nullptr);
    }
    m_instance = newInstance;
    if (m_instance) {
        connect(m_instance, &BaseInstance::runningStatusChanged, this, &ResumePanel::refresh);
        connect(m_instance, &BaseInstance::propertiesChanged, this, &ResumePanel::refresh);
        connect(APPLICATION->stats(), &StatsStore::changed, this, &ResumePanel::refresh, Qt::UniqueConnection);
        connect(m_instance, &QObject::destroyed, this, [this] {
            m_instance = nullptr;
            refresh();
        });
    }
    refresh();
}

void ResumePanel::refresh()
{
    m_empty->setVisible(!m_instance);
    m_content->setVisible(m_instance != nullptr);
    if (!m_instance)
        return;

    m_icon->setPixmap(APPLICATION->icons()->getIcon(m_instance->iconKey()).pixmap(kIconSize, kIconSize));
    m_name->setText(m_instance->name());
    m_version->setText(tr("Minecraft %1 %2 %3").arg(instanceMinecraftVersion(m_instance), kMiddleDot, instanceLoaderName(m_instance)));

    const bool noDays = APPLICATION->settings()->get("ShowGameTimeWithoutDays").toBool();
    const qint64 live = APPLICATION->stats()->activeSeconds(m_instance->id());
    if (m_instance->isRunning()) {
        m_played->setText(tr("Playing for %1 %2 total %3")
                              .arg(Time::prettifyDuration(live, noDays), kMiddleDot,
                                   Time::prettifyDuration(m_instance->totalTimePlayed() + live, noDays)));
    } else if (m_instance->lastLaunch() > 0 && m_instance->lastTimePlayed() > 0) {
        const qint64 ago = qMax<qint64>(0, QDateTime::currentMSecsSinceEpoch() - m_instance->lastLaunch()) / 1000;
        m_played->setText(tr("Played %1, %2 ago %3 total %4")
                              .arg(Time::prettifyDuration(m_instance->lastTimePlayed(), noDays), Time::prettifyDuration(ago, noDays),
                                   kMiddleDot, Time::prettifyDuration(m_instance->totalTimePlayed(), noDays)));
    } else {
        m_played->setText(tr("Never played"));
    }

    const bool running = m_instance->isRunning();
    m_kill->setEnabled(running);
    m_launch->setEnabled(!running && m_instance->canLaunch());
}
