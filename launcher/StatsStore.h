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

#include <QDate>
#include <QList>
#include <QObject>
#include <QPair>
#include <QString>
#include <QVector>

/// One game session. `start` is msecs since epoch, or 0 for playtime imported
/// from before session tracking existed (counts toward totals, has no date).
struct GameSession {
    QString instanceId;
    QString instanceName;
    qint64 start = 0;
    qint64 duration = 0;  // seconds
};

/// Append-only log of game sessions, persisted as a JSON array in `file`.
class StatsStore : public QObject {
    Q_OBJECT
   public:
    explicit StatsStore(const QString& file, QObject* parent = nullptr);

    void load();
    void record(const GameSession& session);

    /// Live tracking: a session opened at launch is counted (and ticked every 30 s) while the game runs.
    void beginSession(const QString& instanceId, const QString& instanceName);
    void endSession(const QString& instanceId, qint64 durationSeconds);
    /// Seconds of the running session for this instance, 0 when it is not running.
    qint64 activeSeconds(const QString& instanceId) const;
    /// Recompute running sessions now and notify listeners (manual refresh).
    void refreshNow() { tick(); }
    void clear();

    /// True when load() found no file - caller may import legacy playtime.
    bool isFresh() const { return m_fresh; }

    const QList<GameSession>& sessions() const { return m_sessions; }

    qint64 totalSeconds() const;
    int launchCount() const;
    qint64 longestSeconds() const;
    qint64 averageSeconds() const;

    /// Seconds played per calendar day for the last `days` days (0 = all dated sessions).
    QList<QPair<QDate, qint64>> secondsPerDay(int days) const;
    /// Seconds played per instance, sorted descending.
    QList<QPair<QString, qint64>> secondsPerInstance() const;
    /// [dayOfWeek 0=Mon..6][hour 0..23] seconds played.
    QVector<QVector<qint64>> heatmap() const;

   signals:
    void changed();

   private:
    void save();

    void tick();

    QString m_file;
    QList<GameSession> m_sessions;
    QList<int> m_active;  // indices into m_sessions of running sessions
    class QTimer* m_timer = nullptr;
    bool m_fresh = false;
};
