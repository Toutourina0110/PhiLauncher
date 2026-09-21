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

#include "InstanceCardDelegate.h"

#include <QAbstractItemView>
#include <QDebug>
#include <QPainter>
#include <QPointer>
#include <QTimer>

#include "InstanceList.h"
#include "InstanceView.h"
#include "MMCTime.h"
#include "minecraft/Component.h"
#include "minecraft/MinecraftInstance.h"
#include "minecraft/PackProfile.h"
#include "modplatform/ModIndex.h"

// defined in InstanceDelegate.cpp
void drawProgressOverlay(QPainter* painter, const QStyleOptionViewItem& option, const int value, const int maximum);

namespace {
constexpr int kMargin = 8;
constexpr int kIconSize = 40;
constexpr int kBorder = 2;
}  // namespace

QString instanceMinecraftVersion(MinecraftInstance* instance)
{
    return instance->getPackProfile()->getComponentVersion("net.minecraft");
}

void ensureProfileLoaded(MinecraftInstance* instance)
{
    auto* profile = instance->getPackProfile();
    // an update/resolve task in flight (e.g. a launch) owns the profile; reload() would abort it
    if (!profile->getComponentVersion("net.minecraft").isEmpty() || profile->getCurrentTask())
        return;
    if (auto res = profile->reload(Net::Mode::Offline); !res)
        qWarning() << "Failed to reload components:" << res.error();
}

QString instanceLoaderName(MinecraftInstance* instance)
{
    auto* profile = instance->getPackProfile();
    for (auto it = Component::KNOWN_MODLOADERS.constBegin(); it != Component::KNOWN_MODLOADERS.constEnd(); ++it) {
        if (profile->getComponentVersion(it.key()).isEmpty())
            continue;
        switch (it.value().type) {
            case ModPlatform::NeoForge:
                return QStringLiteral("NeoForge");
            case ModPlatform::Forge:
                return QStringLiteral("Forge");
            case ModPlatform::Fabric:
                return QStringLiteral("Fabric");
            case ModPlatform::Quilt:
                return QStringLiteral("Quilt");
            case ModPlatform::LiteLoader:
                return QStringLiteral("LiteLoader");
            default:
                break;
        }
    }
    return QObject::tr("Vanilla");
}

QString InstanceCardDelegate::details(MinecraftInstance* instance, const QWidget* view) const
{
    const QString id = instance->id();
    if (auto it = m_details.constFind(id); it != m_details.constEnd())
        return *it;

    if (!m_watched.contains(id)) {
        m_watched.insert(id);
        auto invalidate = [this, id] {
            m_details.remove(id);
            if (m_view)
                m_view->viewport()->update();
        };
        connect(instance, &BaseInstance::propertiesChanged, this, invalidate);
        connect(instance, &QObject::destroyed, this, [this, id] {
            m_details.remove(id);
            m_watched.remove(id);
        });
        auto* profile = instance->getPackProfile();
        connect(profile, &QAbstractItemModel::modelReset, this, invalidate);
        connect(profile, &QAbstractItemModel::rowsInserted, this, invalidate);
        connect(profile, &QAbstractItemModel::rowsRemoved, this, invalidate);
        connect(profile, &QAbstractItemModel::dataChanged, this, invalidate);
    }
    m_view = qobject_cast<const QAbstractItemView*>(view);

    auto compute = [](MinecraftInstance* inst) {
        QStringList parts{ instanceMinecraftVersion(inst), instanceLoaderName(inst) };
        parts.removeAll(QString());
        return parts.join(QString(" %1 ").arg(QChar(0xB7)));
    };
    if (!instanceMinecraftVersion(instance).isEmpty()) {
        m_details.insert(id, compute(instance));
        return m_details[id];
    }
    // profile not loaded: show nothing now, load it once outside paint(), then repaint
    m_details.insert(id, QString());
    QTimer::singleShot(0, this, [this, id, inst = QPointer<MinecraftInstance>(instance), compute] {
        if (!inst)
            return;
        ensureProfileLoaded(inst);
        m_details.insert(id, compute(inst));
        if (m_view)
            m_view->viewport()->update();
    });
    return QString();
}

void InstanceCardDelegate::paint(QPainter* painter, const QStyleOptionViewItem& option, const QModelIndex& index) const
{
    QStyleOptionViewItem opt = option;
    initStyleOption(&opt, index);
    auto* instance = static_cast<MinecraftInstance*>(index.data(InstanceList::InstancePointerRole).value<void*>());
    const bool selected = opt.state.testFlag(QStyle::State_Selected);
    const bool enabled = opt.state.testFlag(QStyle::State_Enabled);
    const bool running = instance && instance->isRunning();
    const QPalette::ColorGroup cg = enabled ? QPalette::Normal : QPalette::Disabled;
    const QRect card = opt.rect;

    painter->save();
    painter->setClipRect(card);
    painter->setRenderHint(QPainter::Antialiasing, false);

    // background: Base, tinted with Highlight when selected
    painter->fillRect(card, opt.palette.color(cg, QPalette::Base));
    QColor border = opt.palette.color(cg, QPalette::Mid);
    if (selected) {
        QColor tint = opt.palette.color(cg, QPalette::Highlight);
        border = tint;
        tint.setAlpha(48);
        painter->fillRect(card, tint);
    } else if (opt.state.testFlag(QStyle::State_MouseOver)) {
        border = opt.palette.color(cg, QPalette::Light);
    }
    painter->setPen(QPen(border, kBorder));
    painter->setBrush(Qt::NoBrush);
    painter->drawRect(card.adjusted(kBorder / 2, kBorder / 2, -kBorder / 2, -kBorder / 2));

    // icon
    QRect iconRect(card.left() + kMargin + kBorder, card.top() + (card.height() - kIconSize) / 2, kIconSize, kIconSize);
    QIcon::Mode mode = !enabled ? QIcon::Disabled : selected ? QIcon::Selected : QIcon::Normal;
    opt.icon.paint(painter, iconRect, Qt::AlignCenter, mode, QIcon::Off);

    // running glyph on the right
    int textRight = card.right() - kMargin - kBorder;
    if (running) {
        QFont glyphFont = opt.font;
        glyphFont.setPointSize(glyphFont.pointSize() + 4);
        painter->setFont(glyphFont);
        painter->setPen(opt.palette.color(cg, QPalette::Highlight));
        const int glyphWidth = QFontMetrics(glyphFont).horizontalAdvance(QString(QChar(0x25B6))) + kMargin;
        painter->drawText(QRect(textRight - glyphWidth, card.top(), glyphWidth, card.height()), Qt::AlignCenter, QString(QChar(0x25B6)));
        textRight -= glyphWidth + kMargin / 2;
    }

    // name (bold) + details (mid color)
    QFont nameFont = opt.font;
    nameFont.setBold(true);
    const QFontMetrics nameMetrics(nameFont);
    const QFontMetrics detailMetrics(opt.font);
    const int textLeft = iconRect.right() + 1 + kMargin;
    const int textWidth = textRight - textLeft;
    const int textHeight = nameMetrics.height() + detailMetrics.height();
    const int textTop = card.top() + (card.height() - textHeight) / 2;

    painter->setFont(nameFont);
    painter->setPen(opt.palette.color(cg, QPalette::Text));
    painter->drawText(QRect(textLeft, textTop, textWidth, nameMetrics.height()), Qt::AlignLeft | Qt::AlignVCenter,
                      nameMetrics.elidedText(opt.text, Qt::ElideRight, textWidth));

    if (instance) {
        QStringList parts{ details(instance, opt.widget), Time::prettifyDuration(instance->totalTimePlayed()) };
        parts.removeAll(QString());
        painter->setFont(opt.font);
        painter->setPen(opt.palette.color(cg, QPalette::Mid));
        painter->drawText(QRect(textLeft, textTop + nameMetrics.height(), textWidth, detailMetrics.height()), Qt::AlignLeft | Qt::AlignVCenter,
                          detailMetrics.elidedText(parts.join(QStringLiteral(" \u00B7 ")), Qt::ElideRight, textWidth));
    }

    QStyleOptionViewItem progressOpt = opt;
    progressOpt.rect = iconRect;
    drawProgressOverlay(painter, progressOpt, index.data(InstanceViewRoles::ProgressValueRole).toInt(),
                        index.data(InstanceViewRoles::ProgressMaximumRole).toInt());

    painter->restore();
}

QSize InstanceCardDelegate::sizeHint([[maybe_unused]] const QStyleOptionViewItem& option, [[maybe_unused]] const QModelIndex& index) const
{
    return cardSize();
}

void InstanceCardDelegate::updateEditorGeometry(QWidget* editor,
                                                const QStyleOptionViewItem& option,
                                                [[maybe_unused]] const QModelIndex& index) const
{
    QRect textRect = option.rect;
    textRect.setLeft(option.rect.left() + kMargin + kBorder + kIconSize + kMargin);
    textRect.adjust(0, kBorder, -kBorder, -kBorder);
    editor->setGeometry(textRect);
}
