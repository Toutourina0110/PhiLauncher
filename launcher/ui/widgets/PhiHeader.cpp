// SPDX-FileCopyrightText: 2026 Phi Launcher contributors
//
// SPDX-License-Identifier: GPL-3.0-only

#include "PhiHeader.h"

#include <QAction>
#include <QHBoxLayout>
#include <QIcon>
#include <QLabel>
#include <QToolButton>

#include "Application.h"

PhiHeader::PhiHeader(QWidget* parent) : QWidget(parent)
{
    setObjectName(QStringLiteral("phiHeader"));
    setAttribute(Qt::WA_StyledBackground, true);  // so the stylesheet can paint the band
    setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Fixed);

    auto row = new QHBoxLayout(this);
    row->setContentsMargins(10, 0, 10, 0);
    row->setSpacing(0);

    auto brand = new QWidget(this);
    brand->setObjectName(QStringLiteral("phiBrand"));
    auto brandRow = new QHBoxLayout(brand);
    brandRow->setContentsMargins(0, 0, 14, 0);
    brandRow->setSpacing(8);
    auto mark = new QLabel(brand);
    mark->setPixmap(APPLICATION->logo().pixmap(24, 24));
    auto word = new QLabel(QStringLiteral("PHI"), brand);
    word->setObjectName(QStringLiteral("phiBrandText"));
    word->setProperty("phiFont", "header");
    brandRow->addWidget(mark);
    brandRow->addWidget(word);
    row->addWidget(brand);

    m_left = new QHBoxLayout();
    m_left->setContentsMargins(14, 0, 0, 0);
    m_left->setSpacing(2);
    row->addLayout(m_left);

    row->addStretch(1);

    m_right = new QHBoxLayout();
    m_right->setContentsMargins(0, 0, 0, 0);
    m_right->setSpacing(2);
    row->addLayout(m_right);
}

QToolButton* PhiHeader::addEntry(QAction* action, const QString& icon)
{
    auto button = makeButton(action, icon);
    m_left->addWidget(button);
    return button;
}

QToolButton* PhiHeader::addTrailingEntry(QAction* action, const QString& icon)
{
    auto button = makeButton(action, icon);
    button->setObjectName(QStringLiteral("phiHeaderTrailing"));
    m_right->addWidget(button);
    return button;
}

QToolButton* PhiHeader::makeButton(QAction* action, const QString& icon)
{
    auto button = new QToolButton(this);
    button->setDefaultAction(action);
    button->setToolButtonStyle(Qt::ToolButtonTextBesideIcon);
    button->setIconSize(QSize(20, 20));
    button->setAutoRaise(true);
    button->setFocusPolicy(Qt::NoFocus);
    // the pixel icons are ours; the user's icon theme only applies to the rest of the UI.
    // An empty name keeps whatever the action carries (the account entry shows the player's face).
    if (!icon.isEmpty()) {
        auto ours = QIcon(":/phi/icons/" + icon + ".svg");
        button->setIcon(ours);
        connect(action, &QAction::changed, button, [button, ours] { button->setIcon(ours); });
    }
    m_buttons.insert(action, button);
    return button;
}
