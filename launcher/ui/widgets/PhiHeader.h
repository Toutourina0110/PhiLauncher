// SPDX-FileCopyrightText: 2026 Phi Launcher contributors
//
// SPDX-License-Identifier: GPL-3.0-only

#pragma once

#include <QHash>
#include <QWidget>

class QAction;
class QHBoxLayout;
class QToolButton;

/**
 * The Phi navigation header: a fixed band at the top of the main window with the Phi mark on the left,
 * the navigation entries next to it and the account on the right. It replaces the Qt tool bar the fork
 * inherited, so it is not movable, has no overflow chevron and does not use the icon theme: every entry
 * draws the pixel icon shipped in ":/phi/icons".
 */
class PhiHeader : public QWidget {
    Q_OBJECT
   public:
    explicit PhiHeader(QWidget* parent = nullptr);

    /** Adds a navigation entry drawn with ":/phi/icons/<icon>.svg"; the action keeps its text, menu and slots. */
    QToolButton* addEntry(QAction* action, const QString& icon);
    /** Same, but pinned to the right of the header (the account entry). */
    QToolButton* addTrailingEntry(QAction* action, const QString& icon);

    QToolButton* buttonFor(QAction* action) const { return m_buttons.value(action); }

   private:
    QToolButton* makeButton(QAction* action, const QString& icon);

    QHBoxLayout* m_left;
    QHBoxLayout* m_right;
    QHash<QAction*, QToolButton*> m_buttons;
};
