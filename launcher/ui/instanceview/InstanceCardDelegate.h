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

#include <QHash>
#include <QPointer>
#include <QSet>
#include <QSize>
#include <QString>

#include "InstanceDelegate.h"

class MinecraftInstance;
class QAbstractItemView;

/// Minecraft version of the instance ("1.21.1"); empty while the pack profile is not loaded.
QString instanceMinecraftVersion(MinecraftInstance* instance);
/// Display name of the instance's mod loader ("Fabric"), or "Vanilla".
QString instanceLoaderName(MinecraftInstance* instance);
/// Loads the pack profile offline if it has not been loaded yet. Does file I/O: never call from paint().
void ensureProfileLoaded(MinecraftInstance* instance);

/// Paints an instance as a wide card: icon, bold name, "version - loader - playtime".
/// Inherits the rename editor from ListViewDelegate.
class InstanceCardDelegate : public ListViewDelegate {
    Q_OBJECT

   public:
    using ListViewDelegate::ListViewDelegate;

    static QSize cardSize() { return { 250, 60 }; }

    void paint(QPainter* painter, const QStyleOptionViewItem& option, const QModelIndex& index) const override;
    QSize sizeHint(const QStyleOptionViewItem& option, const QModelIndex& index) const override;
    void updateEditorGeometry(QWidget* editor, const QStyleOptionViewItem& option, const QModelIndex& index) const override;

   private:
    /// "version · loader" for the card, cached per instance id; never does I/O (unloaded profiles load on the event loop).
    QString details(MinecraftInstance* instance, const QWidget* view) const;

    mutable QHash<QString, QString> m_details;
    mutable QSet<QString> m_watched;  // instance ids whose change signals invalidate m_details
    mutable QPointer<const QAbstractItemView> m_view;
};
