/* Copyright 2013-2021 MultiMC Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <QEvent>
#include <QListView>
#include <QPainter>
#include <QScrollBar>
#include <QStyledItemDelegate>

#include "ui/pages/BasePage.h"

const int pageIconSize = 24;

class PageViewDelegate : public QStyledItemDelegate {
   public:
    PageViewDelegate(QObject* parent) : QStyledItemDelegate(parent) {}
    QSize sizeHint(const QStyleOptionViewItem& option, const QModelIndex& index) const
    {
        QSize size = QStyledItemDelegate::sizeHint(option, index);
        size.setHeight(qMax(size.height(), 32));
        return size;
    }
};

class PageModel : public QAbstractListModel {
   public:
    enum Roles { DescriptionRole = Qt::UserRole + 1 };

    PageModel(QObject* parent = 0) : QAbstractListModel(parent)
    {
        QPixmap empty(pageIconSize, pageIconSize);
        empty.fill(Qt::transparent);
        m_emptyIcon = QIcon(empty);
    }
    virtual ~PageModel() {}

    int rowCount(const QModelIndex& parent = QModelIndex()) const { return parent.isValid() ? 0 : m_pages.size(); }
    QVariant data(const QModelIndex& index, int role = Qt::DisplayRole) const
    {
        switch (role) {
            case Qt::DisplayRole:
                return m_pages.at(index.row())->displayName();
            case DescriptionRole:
                return m_pages.at(index.row())->description();
            case Qt::DecorationRole: {
                QIcon icon = m_pages.at(index.row())->icon();
                if (icon.isNull())
                    icon = m_emptyIcon;
                // HACK: fixes icon stretching on windows. TODO: report Qt bug for this
                return QIcon(icon.pixmap(QSize(48, 48)));
            }
        }
        return QVariant();
    }

    void setPages(const QList<BasePage*>& pages)
    {
        beginResetModel();
        m_pages = pages;
        endResetModel();
    }
    const QList<BasePage*>& pages() const { return m_pages; }

    BasePage* findPageEntryById(QString id)
    {
        for (auto page : m_pages) {
            if (page->id() == id)
                return page;
        }
        return nullptr;
    }

    QList<BasePage*> m_pages;
    QIcon m_emptyIcon;
};

/// Card look: 32px icon on the left, bold title, one-line description in the mid color below it.
class PageCardDelegate : public QStyledItemDelegate {
   public:
    static constexpr int iconSize = 32;
    static constexpr int padding = 8;

    PageCardDelegate(QObject* parent) : QStyledItemDelegate(parent) {}

    /// Tile mode: the card fills its grid cell, big icon on top, centered title and wrapped description.
    void setTileMode(bool tiles) { m_tiles = tiles; }
    void setTileSize(const QSize& size) { m_tileSize = size; }

    QSize sizeHint(const QStyleOptionViewItem& /*option*/, const QModelIndex& index) const override
    {
        if (m_tiles)
            return m_tileSize;
        const bool hasDesc = !index.data(PageModel::DescriptionRole).toString().isEmpty();
        return QSize(280, hasDesc ? 52 : 48);
    }

    void paintTile(QPainter* painter, const QStyleOptionViewItem& opt, const QModelIndex& index) const
    {
        const bool selected = opt.state & QStyle::State_Selected;
        const QPalette& pal = opt.palette;
        const int bigIcon = 56;

        painter->fillRect(opt.rect, selected ? pal.highlight() : pal.alternateBase());
        painter->setPen(QPen(selected ? pal.highlight().color() : pal.mid().color(), 1));
        painter->drawRect(opt.rect.adjusted(0, 0, -1, -1));

        const QRect content = opt.rect.adjusted(padding * 2, padding * 2, -padding * 2, -padding * 2);
        const QString title = index.data(Qt::DisplayRole).toString();
        const QString desc = index.data(PageModel::DescriptionRole).toString();
        QFont titleFont = opt.font;
        titleFont.setBold(true);
        titleFont.setPointSize(titleFont.pointSize() + 1);
        const QFontMetrics titleFm(titleFont);
        const QFontMetrics descFm(opt.font);
        const QRect descBounds = descFm.boundingRect(QRect(0, 0, content.width(), 1000), Qt::TextWordWrap | Qt::AlignHCenter, desc);
        const int total = bigIcon + padding + titleFm.height() + (desc.isEmpty() ? 0 : 2 + descBounds.height());
        int y = content.top() + qMax(0, (content.height() - total) / 2);

        opt.icon.paint(painter, QRect(content.center().x() - bigIcon / 2, y, bigIcon, bigIcon), Qt::AlignCenter,
                       selected ? QIcon::Selected : QIcon::Normal);
        y += bigIcon + padding;
        painter->setFont(titleFont);
        painter->setPen(selected ? pal.highlightedText().color() : pal.text().color());
        painter->drawText(QRect(content.left(), y, content.width(), titleFm.height()), Qt::AlignHCenter | Qt::AlignVCenter,
                          titleFm.elidedText(title, Qt::ElideRight, content.width()));
        y += titleFm.height() + 2;
        if (!desc.isEmpty()) {
            painter->setFont(opt.font);
            painter->setPen(selected ? pal.highlightedText().color() : pal.mid().color());
            painter->drawText(QRect(content.left(), y, content.width(), content.bottom() - y + 1), Qt::TextWordWrap | Qt::AlignHCenter | Qt::AlignTop,
                              desc);
        }
    }

    void paint(QPainter* painter, const QStyleOptionViewItem& option, const QModelIndex& index) const override
    {
        QStyleOptionViewItem opt = option;
        initStyleOption(&opt, index);
        const bool selected = opt.state & QStyle::State_Selected;
        const QPalette& pal = opt.palette;

        painter->save();
        painter->setRenderHint(QPainter::Antialiasing, false);
        if (m_tiles) {
            paintTile(painter, opt, index);
            painter->restore();
            return;
        }
        painter->fillRect(opt.rect, selected ? pal.highlight() : pal.alternateBase());
        painter->setPen(QPen(selected ? pal.highlight().color() : pal.mid().color(), 1));
        painter->drawRect(opt.rect.adjusted(0, 0, -1, -1));

        const QRect content = opt.rect.adjusted(padding, padding, -padding, -padding);
        const QRect iconRect(content.left(), content.top() + (content.height() - iconSize) / 2, iconSize, iconSize);
        opt.icon.paint(painter, iconRect, Qt::AlignCenter, selected ? QIcon::Selected : QIcon::Normal);

        const QString title = index.data(Qt::DisplayRole).toString();
        const QString desc = index.data(PageModel::DescriptionRole).toString();
        const int textLeft = iconRect.right() + 1 + padding;
        const QRect textRect(textLeft, content.top(), content.right() - textLeft + 1, content.height());

        QFont titleFont = opt.font;
        titleFont.setBold(true);
        const QFontMetrics titleFm(titleFont);
        const QFontMetrics descFm(opt.font);

        if (desc.isEmpty()) {
            painter->setFont(titleFont);
            painter->setPen(selected ? pal.highlightedText().color() : pal.text().color());
            painter->drawText(textRect, Qt::AlignLeft | Qt::AlignVCenter, titleFm.elidedText(title, Qt::ElideRight, textRect.width()));
        } else {
            const int total = titleFm.height() + descFm.height();
            int y = textRect.top() + (textRect.height() - total) / 2;
            painter->setFont(titleFont);
            painter->setPen(selected ? pal.highlightedText().color() : pal.text().color());
            painter->drawText(QRect(textRect.left(), y, textRect.width(), titleFm.height()), Qt::AlignLeft | Qt::AlignVCenter,
                              titleFm.elidedText(title, Qt::ElideRight, textRect.width()));
            y += titleFm.height();
            painter->setFont(opt.font);
            painter->setPen(selected ? pal.highlightedText().color() : pal.mid().color());
            painter->drawText(QRect(textRect.left(), y, textRect.width(), descFm.height()), Qt::AlignLeft | Qt::AlignVCenter,
                              descFm.elidedText(desc, Qt::ElideRight, textRect.width()));
        }
        painter->restore();
    }

   private:
    bool m_tiles = false;
    QSize m_tileSize{ 280, 130 };
};

class PageView : public QListView {
   public:
    PageView(QWidget* parent = 0) : QListView(parent)
    {
        setSizePolicy(QSizePolicy::MinimumExpanding, QSizePolicy::Expanding);
        setItemDelegate(new PageViewDelegate(this));
        setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    }

    virtual QSize sizeHint() const
    {
        int width = sizeHintForColumn(0) + frameWidth() * 2 + 5;
        if (verticalScrollBar()->isVisible())
            width += verticalScrollBar()->width();
        return QSize(width, 100);
    }

    virtual bool eventFilter(QObject* obj, QEvent* event)
    {
        if (obj == verticalScrollBar() && (event->type() == QEvent::Show || event->type() == QEvent::Hide))
            updateGeometry();
        return QListView::eventFilter(obj, event);
    }
};
