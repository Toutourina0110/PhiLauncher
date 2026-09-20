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

#include "StatsStore.h"

#include <QDateTime>
#include <QDebug>
#include <QFile>
#include <QJsonArray>
#include <QJsonObject>
#include <QMap>
#include <QTimer>

#include "Json.h"

StatsStore::StatsStore(const QString& file, QObject* parent) : QObject(parent), m_file(file)
{
    m_timer = new QTimer(this);
    m_timer->setInterval(30 * 1000);
    connect(m_timer, &QTimer::timeout, this, &StatsStore::tick);
}

void StatsStore::beginSession(const QString& instanceId, const QString& instanceName)
{
    m_sessions.append({ instanceId, instanceName, QDateTime::currentMSecsSinceEpoch(), 0 });
    m_active.append(m_sessions.size() - 1);
    m_fresh = false;
    if (!m_timer->isActive())
        m_timer->start();
    emit changed();
}

void StatsStore::endSession(const QString& instanceId, qint64 durationSeconds)
{
    for (int i = m_active.size() - 1; i >= 0; i--) {
        int idx = m_active[i];
        if (m_sessions[idx].instanceId != instanceId)
            continue;
        m_sessions[idx].duration = durationSeconds;
        m_active.removeAt(i);
        if (durationSeconds <= 0) {
            m_sessions.removeAt(idx);  // nothing worth keeping
            for (int& other : m_active)
                if (other > idx)
                    other--;
        }
        if (m_active.isEmpty())
            m_timer->stop();
        save();
        emit changed();
        return;
    }
    // no live session found (RecordGameTime toggled mid-run?): fall back to a plain record
    record({ instanceId, QString(), QDateTime::currentMSecsSinceEpoch() - durationSeconds * 1000, durationSeconds });
}

qint64 StatsStore::activeSeconds(const QString& instanceId) const
{
    for (int idx : m_active)
        if (m_sessions[idx].instanceId == instanceId)
            return m_sessions[idx].duration;
    return 0;
}

void StatsStore::tick()
{
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    for (int idx : m_active)
        m_sessions[idx].duration = (now - m_sessions[idx].start) / 1000;
    save();  // keeps the running session if the launcher dies mid-game
    emit changed();
}

void StatsStore::load()
{
    m_sessions.clear();
    if (!QFile::exists(m_file)) {
        m_fresh = true;
        return;
    }
    auto doc = Json::requireDocument(m_file, "sessions");
    if (!doc) {
        qWarning() << "Failed to read" << m_file << ":" << doc.error();
        return;
    }
    auto arr = Json::requireArray(*doc, "sessions");
    if (!arr) {
        qWarning() << "Failed to parse" << m_file << ":" << arr.error();
        return;
    }
    for (const auto& v : *arr) {
        auto o = v.toObject();
        GameSession s;
        s.instanceId = o.value("instance").toString();
        s.instanceName = o.value("name").toString();
        s.start = static_cast<qint64>(o.value("start").toDouble());
        s.duration = static_cast<qint64>(o.value("duration").toDouble());
        if (s.duration > 0)
            m_sessions.append(s);
    }
}

void StatsStore::record(const GameSession& session)
{
    if (session.duration <= 0)
        return;
    m_sessions.append(session);
    m_fresh = false;
    save();
    emit changed();
}

void StatsStore::clear()
{
    m_sessions.clear();
    save();
    emit changed();
}

void StatsStore::save()
{
    QJsonArray arr;
    for (const auto& s : m_sessions) {
        QJsonObject o;
        o["instance"] = s.instanceId;
        o["name"] = s.instanceName;
        o["start"] = static_cast<double>(s.start);
        o["duration"] = static_cast<double>(s.duration);
        arr.append(o);
    }
    auto res = Json::write(arr, m_file);
    if (!res)
        qWarning() << "Failed to write" << m_file << ":" << res.error();
}

qint64 StatsStore::totalSeconds() const
{
    qint64 total = 0;
    for (const auto& s : m_sessions)
        total += s.duration;
    return total;
}

int StatsStore::launchCount() const
{
    int n = 0;
    for (const auto& s : m_sessions)
        if (s.start > 0)
            n++;
    return n;
}

qint64 StatsStore::longestSeconds() const
{
    qint64 best = 0;
    for (const auto& s : m_sessions)
        if (s.start > 0 && s.duration > best)
            best = s.duration;
    return best;
}

qint64 StatsStore::averageSeconds() const
{
    qint64 total = 0;
    int n = 0;
    for (const auto& s : m_sessions) {
        if (s.start > 0) {
            total += s.duration;
            n++;
        }
    }
    return n ? total / n : 0;
}

QList<QPair<QDate, qint64>> StatsStore::secondsPerDay(int days) const
{
    QMap<QDate, qint64> perDay;
    QDate today = QDate::currentDate();
    QDate first;
    if (days > 0) {
        first = today.addDays(-(days - 1));
        for (QDate d = first; d <= today; d = d.addDays(1))
            perDay[d] = 0;
    }
    for (const auto& s : m_sessions) {
        if (s.start <= 0)
            continue;
        QDate d = QDateTime::fromMSecsSinceEpoch(s.start).date();
        if (days > 0 && d < first)
            continue;
        perDay[d] += s.duration;
    }
    QList<QPair<QDate, qint64>> out;
    for (auto it = perDay.constBegin(); it != perDay.constEnd(); ++it)
        out.append({ it.key(), it.value() });
    return out;
}

QList<QPair<QString, qint64>> StatsStore::secondsPerInstance() const
{
    QMap<QString, qint64> perInst;
    QMap<QString, QString> names;
    for (const auto& s : m_sessions) {
        perInst[s.instanceId] += s.duration;
        if (!s.instanceName.isEmpty())
            names[s.instanceId] = s.instanceName;  // latest name wins
    }
    QList<QPair<QString, qint64>> out;
    for (auto it = perInst.constBegin(); it != perInst.constEnd(); ++it)
        out.append({ names.value(it.key(), it.key()), it.value() });
    std::sort(out.begin(), out.end(), [](const auto& a, const auto& b) { return a.second > b.second; });
    return out;
}

QVector<QVector<qint64>> StatsStore::heatmap() const
{
    QVector<QVector<qint64>> grid(7, QVector<qint64>(24, 0));
    for (const auto& s : m_sessions) {
        if (s.start <= 0)
            continue;
        // spread the session over every hour slot it overlaps
        QDateTime t = QDateTime::fromMSecsSinceEpoch(s.start);
        qint64 left = s.duration;
        while (left > 0) {
            qint64 untilNextHour = 3600 - (t.time().minute() * 60 + t.time().second());
            qint64 chunk = std::min(left, untilNextHour);
            grid[t.date().dayOfWeek() - 1][t.time().hour()] += chunk;
            left -= chunk;
            t = t.addSecs(chunk);
        }
    }
    return grid;
}
