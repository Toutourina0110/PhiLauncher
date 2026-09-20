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

#include <QFrame>

class MinecraftInstance;
class QLabel;
class QPushButton;
class QToolButton;

/// "Resume" hero: big icon, name, version/loader, playtime and the instance actions.
/// Only displays the instance it is given; the owner decides which one that is.
class ResumePanel : public QFrame {
    Q_OBJECT

   public:
    explicit ResumePanel(QWidget* parent = nullptr);

    /// nullptr shows the "no instance yet" state.
    void setInstance(MinecraftInstance* newInstance);
    MinecraftInstance* instance() const { return m_instance; }

   signals:
    void launchRequested();
    void killRequested();
    void editRequested();
    void folderRequested();
    void changeGroupRequested();
    void exportRequested();
    void copyRequested();
    void deleteRequested();
    void shortcutRequested();

   private:
    void refresh();

    MinecraftInstance* m_instance = nullptr;

    QLabel* m_empty = nullptr;
    QWidget* m_content = nullptr;
    QLabel* m_icon = nullptr;
    QLabel* m_name = nullptr;
    QLabel* m_version = nullptr;
    QLabel* m_played = nullptr;
    QPushButton* m_launch = nullptr;
    QToolButton* m_kill = nullptr;
};
