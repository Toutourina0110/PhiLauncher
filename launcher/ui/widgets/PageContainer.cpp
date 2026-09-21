// SPDX-License-Identifier: GPL-3.0-only
/*
 *  Prism Launcher - Minecraft Launcher
 *  Copyright (C) 2022 Sefa Eyeoglu <contact@scrumplex.net>
 *  Copyright (c) 2022 Jamie Mansfield <jmansfield@cadixdev.org>
 *  Copyright (C) 2023 TheKodeToad <TheKodeToad@proton.me>
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
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 *      Copyright 2013-2021 MultiMC Contributors
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

#include "PageContainer.h"
#include "BuildConfig.h"
#include "PageContainer_p.h"

#include <QDialogButtonBox>
#include <QGridLayout>
#include <QLabel>
#include <QLineEdit>
#include <QListView>
#include <QPushButton>
#include <QSortFilterProxyModel>
#include <QStackedLayout>
#include <QStyledItemDelegate>
#include <QUrl>
#include <utility>

#include "settings/SettingsObject.h"

#include "ui/widgets/IconLabel.h"

#include "Application.h"
#include "DesktopServices.h"

class PageEntryFilterModel : public QSortFilterProxyModel {
   public:
    explicit PageEntryFilterModel(QObject* parent = nullptr) : QSortFilterProxyModel(parent) {}

   protected:
    bool filterAcceptsRow(int sourceRow, const QModelIndex& sourceParent) const override
    {
        const QString pattern = filterRegularExpression().pattern();
        auto* const model = static_cast<PageModel*>(sourceModel());
        auto* const page = model->pages().at(sourceRow);
        if (!page->shouldDisplay()) {
            return false;
        }
        // Regular contents check, then check page-filter.
        return QSortFilterProxyModel::filterAcceptsRow(sourceRow, sourceParent);
    }
};

PageContainer::PageContainer(BasePageProvider* pageProvider, QString defaultId, QWidget* parent)
    : QWidget(parent)
    , m_proxyModel(new PageEntryFilterModel(this))
    , m_model(new PageModel(this))
{
    createUI();
    useSidebarStyle(true);

    int counter = 0;
    auto pages = pageProvider->getPages();
    for (auto* page : pages) {
        auto* widget = dynamic_cast<QWidget*>(page);
        widget->setParent(this);
        page->stackIndex = m_pageStack->addWidget(widget);
        page->listIndex = counter;
        page->setParentContainer(this);
        counter++;
        page->updateExtraInfo = [this](const QString& id, const QString& info) {
            if (m_currentPage && id == m_currentPage->id()) {
                updateHeader(info);
            }
        };
    }
    m_model->setPages(pages);

    m_proxyModel->setSourceModel(m_model);
    m_proxyModel->setFilterCaseSensitivity(Qt::CaseInsensitive);

    m_pageList->setIconSize(QSize(pageIconSize, pageIconSize));
    m_pageList->setSelectionMode(QAbstractItemView::SingleSelection);
    m_pageList->setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
    m_pageList->setSizeAdjustPolicy(QAbstractScrollArea::AdjustToContents);
    m_pageList->setModel(m_proxyModel);
    connect(m_pageList->selectionModel(), &QItemSelectionModel::currentRowChanged, this, &PageContainer::currentChanged);
    connect(m_pageList, &QAbstractItemView::activated, this, [this](const QModelIndex&) { emit pageActivated(); });
    m_pageStack->setStackingMode(QStackedLayout::StackOne);
    m_pageList->setFocus();
    selectPage(std::move(defaultId));
}

bool PageContainer::selectPage(QString pageId)
{
    // now find what we want to have selected...
    auto* page = m_model->findPageEntryById(pageId);
    QModelIndex index;
    if (page) {
        index = m_proxyModel->mapFromSource(m_model->index(page->listIndex));
    }
    if (!index.isValid()) {
        index = m_proxyModel->index(0, 0);
    }
    if (index.isValid()) {
        m_pageList->setCurrentIndex(index);
        return true;
    }
    return false;
}

BasePage* PageContainer::getPage(QString pageId)
{
    return m_model->findPageEntryById(pageId);
}

BasePage* PageContainer::selectedPage() const
{
    return m_currentPage;
}

const QList<BasePage*>& PageContainer::getPages() const
{
    return m_model->pages();
}

void PageContainer::refreshContainer()
{
    m_proxyModel->invalidate();
    if (!m_currentPage || !m_currentPage->shouldDisplay()) {
        auto index = m_proxyModel->index(0, 0);
        if (index.isValid()) {
            m_pageList->setCurrentIndex(index);
        } else {
            // FIXME: unhandled corner case: what to do when there's no page to select?
        }
    }
}

void PageContainer::createUI()
{
    m_pageStack = new QStackedLayout;
    m_pageList = new PageView;
    m_header = new QLabel();

    QFont headerLabelFont = m_header->font();
    headerLabelFont.setBold(true);
    const int pointSize = headerLabelFont.pointSize();
    if (pointSize > 0) {
        headerLabelFont.setPointSize(pointSize + 2);
    }
    m_header->setFont(headerLabelFont);
    m_header->setProperty("phiFont", "header");

    auto* headerHLayout = new QHBoxLayout;
    const int leftMargin = APPLICATION->style()->pixelMetric(QStyle::PM_LayoutLeftMargin);
    headerHLayout->addSpacerItem(new QSpacerItem(leftMargin, 0, QSizePolicy::Fixed, QSizePolicy::Ignored));
    headerHLayout->addWidget(m_header);
    headerHLayout->setContentsMargins(0, 6, 0, 0);

    m_pageStack->setContentsMargins(0, 0, 0, 0);
    m_pageStack->addWidget(new QWidget(this));

    m_layout = new QGridLayout;
    m_layout->addLayout(headerHLayout, 0, 1, 1, 1);
    m_layout->addWidget(m_pageList, 0, 0, 3, 1);
    m_layout->addLayout(m_pageStack, 1, 1, 1, 1);
    m_layout->setColumnStretch(1, 4);
    m_layout->setContentsMargins(0, 0, 0, 0);
    setLayout(m_layout);
}

void PageContainer::updateHeader(const QString& extraInfo)
{
    if (!m_currentPage) {
        m_header->setText(QString());
        return;
    }
    const QString title = m_currentPage->displayName() + extraInfo;
    if (m_breadcrumbRoot.isEmpty()) {
        m_header->setText(title);
    } else {
        // U+203A "single right-pointing angle quotation mark"
        m_header->setText(QStringLiteral("%1 %2 %3").arg(m_breadcrumbRoot, QChar(0x203A), title));
    }
}

void PageContainer::retranslate()
{
    updateHeader();

    for (auto* page : m_model->pages()) {
        page->retranslate();
    }
}

void PageContainer::addButtons(QWidget* buttons)
{
    m_layout->addWidget(buttons, 2, 1, 1, 2);
}

void PageContainer::addButtons(QLayout* buttons)
{
    m_layout->addLayout(buttons, 2, 1, 1, 2);
}

void PageContainer::useSidebarStyle(bool sidebar)
{
    m_pageList->setProperty("_kde_side_panel_view", sidebar);
}

void PageContainer::useCardStyle(bool cards)
{
    auto* oldDelegate = m_pageList->itemDelegate();
    if (cards) {
        m_pageList->setItemDelegate(new PageCardDelegate(m_pageList));
        m_pageList->setSpacing(4);
        m_pageList->setUniformItemSizes(true);
        m_pageList->setMinimumWidth(290);
    } else {
        m_pageList->setItemDelegate(new PageViewDelegate(m_pageList));
        m_pageList->setSpacing(0);
        m_pageList->setUniformItemSizes(false);
        m_pageList->setMinimumWidth(0);
    }
    delete oldDelegate;
    m_pageList->updateGeometry();
}

void PageContainer::showListOnly()
{
    m_pageList->show();
    m_header->hide();
    if (auto* w = m_pageStack->currentWidget())
        w->hide();
    for (int i = 0; i < m_pageStack->count(); i++)
        m_pageStack->widget(i)->hide();
    m_layout->addWidget(m_pageList, 0, 0, 3, 2);  // span both columns
}

void PageContainer::showPageOnly()
{
    m_pageList->hide();
    m_header->show();
    if (auto* w = m_pageStack->currentWidget())
        w->show();
    m_layout->addWidget(m_pageList, 0, 0, 3, 1);
}

void PageContainer::useGridList(bool grid)
{
    if (grid) {
        m_pageList->setViewMode(QListView::IconMode);
        m_pageList->setFlow(QListView::LeftToRight);
        m_pageList->setWrapping(true);
        m_pageList->setResizeMode(QListView::Adjust);
        m_pageList->setMovement(QListView::Static);
        m_pageList->setSpacing(0);  // the tile delegate draws its own gap
        m_pageList->setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);  // rows are sized to fit
        m_pageList->setUniformItemSizes(false);
        m_pageList->setMinimumWidth(0);
        m_pageList->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding);
        m_pageList->setSizeAdjustPolicy(QAbstractScrollArea::AdjustIgnored);
        if (auto* d = dynamic_cast<PageCardDelegate*>(m_pageList->itemDelegate()))
            d->setTileMode(true);
        m_pageList->viewport()->installEventFilter(this);
        updateGridSize();
    } else {
        m_pageList->setViewMode(QListView::ListMode);
        m_pageList->setFlow(QListView::TopToBottom);
        m_pageList->setWrapping(false);
        m_pageList->setGridSize(QSize());
    }
}

/// Tiles fill the viewport exactly: 3 columns, rows sized so every page fits without scrolling (min 110px).
void PageContainer::updateGridSize()
{
    const int cols = 3;
    const int count = m_proxyModel->rowCount();
    const int rows = qMax(1, (count + cols - 1) / cols);
    const QSize vp = m_pageList->viewport()->size();
    // a couple of px of slack: IconMode wraps a cell that touches the viewport edge
    const int w = qMax(120, (vp.width() - 4) / cols);
    const int h = qMax(110, (vp.height() - 4) / rows);
    const QSize grid(w, h);
    if (grid == m_pageList->gridSize())
        return;  // viewport resize that did not change the tiles: no re-layout
    if (auto* d = dynamic_cast<PageCardDelegate*>(m_pageList->itemDelegate()))
        d->setTileSize(grid);
    m_pageList->setGridSize(grid);
    m_pageList->doItemsLayout();
}

bool PageContainer::eventFilter(QObject* watched, QEvent* event)
{
    if (watched == m_pageList->viewport() && event->type() == QEvent::Resize && m_pageList->viewMode() == QListView::IconMode)
        updateGridSize();
    return QWidget::eventFilter(watched, event);
}

void PageContainer::setBreadcrumbRoot(const QString& root)
{
    m_breadcrumbRoot = root;
    updateHeader();
}

void PageContainer::showPage(int row)
{
    if (m_currentPage) {
        m_currentPage->closed();
    }
    if (row != -1) {
        m_currentPage = m_model->pages().at(row);
    } else {
        m_currentPage = nullptr;
    }
    updateHeader();
    if (m_currentPage) {
        m_pageStack->setCurrentIndex(m_currentPage->stackIndex);
        m_currentPage->opened();
    } else {
        m_pageStack->setCurrentIndex(0);
    }
}

void PageContainer::help()
{
    if (m_currentPage) {
        QString pageId = m_currentPage->helpPage();
        if (pageId.isEmpty()) {
            return;
        }
        DesktopServices::openUrl(QUrl(BuildConfig.HELP_URL.arg(pageId)));
    }
}

void PageContainer::currentChanged(const QModelIndex& current)
{
    int selectedIndex = current.isValid() ? m_proxyModel->mapToSource(current).row() : -1;

    auto* selected = selectedIndex >= 0 ? m_model->pages().at(selectedIndex) : nullptr;
    auto* previous = m_currentPage;

    emit selectedPageChanged(previous, selected);

    showPage(selectedIndex);
}

bool PageContainer::prepareToClose()
{
    if (!saveAll()) {
        return false;
    }
    if (m_currentPage) {
        m_currentPage->closed();
    }
    return true;
}

bool PageContainer::saveAll()
{
    for (auto* page : m_model->pages()) {
        if (!page->apply()) {
            return false;
        }
    }
    return true;
}

void PageContainer::changeEvent(QEvent* event)
{
    if (event->type() == QEvent::LanguageChange) {
        retranslate();
    }
    QWidget::changeEvent(event);
}
