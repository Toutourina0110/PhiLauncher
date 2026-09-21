#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QTest>

#include <StatsStore.h>

class StatsStoreTest : public QObject {
    Q_OBJECT
   private slots:
    void test_RecordLoadAggregate()
    {
        QTemporaryDir dir;
        QString file = dir.filePath("sessions.json");

        qint64 now = QDateTime::currentMSecsSinceEpoch();
        {
            StatsStore store(file);
            store.load();
            QVERIFY(store.isFresh());
            store.record({ "a", "Alpha", 0, 100 });      // legacy import, no date
            store.record({ "a", "Alpha", now, 600 });    // today
            store.record({ "b", "Beta", now - 86400000LL, 300 });  // yesterday
            store.record({ "b", "Beta", now, 0 });       // ignored
            QVERIFY(!store.isFresh());
        }

        StatsStore store(file);
        store.load();
        QVERIFY(!store.isFresh());
        QCOMPARE(store.sessions().size(), qsizetype(3));
        QCOMPARE(store.totalSeconds(), qint64(1000));
        QCOMPARE(store.launchCount(), 2);
        QCOMPARE(store.longestSeconds(), qint64(600));
        QCOMPARE(store.averageSeconds(), qint64(450));

        auto perInst = store.secondsPerInstance();
        QCOMPARE(perInst.size(), qsizetype(2));
        QCOMPARE(perInst[0].first, QString("Alpha"));
        QCOMPARE(perInst[0].second, qint64(700));
        QCOMPARE(perInst[1].second, qint64(300));

        auto days = store.secondsPerDay(7);
        QCOMPARE(days.size(), qsizetype(7));
        QCOMPARE(days.last().second, qint64(600));
        QCOMPARE(days[5].second, qint64(300));

        auto all = store.secondsPerDay(0);
        QCOMPARE(all.size(), qsizetype(2));

        qint64 heat = 0;
        for (const auto& row : store.heatmap())
            for (qint64 v : row)
                heat += v;
        QCOMPARE(heat, qint64(900));  // dated sessions only, fully distributed across hour slots
    }

    void test_LiveSession()
    {
        QTemporaryDir dir;
        QString file = dir.filePath("sessions.json");
        StatsStore store(file);
        store.load();
        QSignalSpy changed(&store, &StatsStore::changed);

        store.beginSession("a", "Alpha");
        QCOMPARE(changed.count(), 1);
        QCOMPARE(store.sessions().size(), qsizetype(1));
        QCOMPARE(store.activeSeconds("a"), qint64(0));
        QCOMPARE(store.activeSeconds("b"), qint64(0));

        store.refreshNow();  // tick: recompute + save + notify
        QCOMPARE(changed.count(), 2);
        QVERIFY(store.activeSeconds("a") >= 0);
        QVERIFY(QFile::exists(file));

        store.endSession("a", 42);
        QCOMPARE(changed.count(), 3);
        QCOMPARE(store.activeSeconds("a"), qint64(0));
        QCOMPARE(store.sessions().size(), qsizetype(1));
        QCOMPARE(store.sessions().first().duration, qint64(42));
        QCOMPARE(store.totalSeconds(), qint64(42));

        // a session that ends with nothing played is dropped, not kept as a 0 s row
        store.beginSession("b", "Beta");
        store.endSession("b", 0);
        QCOMPARE(store.sessions().size(), qsizetype(1));

        // ending an instance with no live session falls back to a plain record
        store.endSession("c", 7);
        QCOMPARE(store.sessions().size(), qsizetype(2));
        QCOMPARE(store.totalSeconds(), qint64(49));

        StatsStore reloaded(file);
        reloaded.load();
        QCOMPARE(reloaded.totalSeconds(), qint64(49));
    }

    void test_AtomicSave()
    {
        QTemporaryDir dir;
        QString file = dir.filePath("sessions.json");
        StatsStore store(file);
        store.load();
        store.record({ "a", "Alpha", QDateTime::currentMSecsSinceEpoch(), 10 });

        QVERIFY(QFile::exists(file));
        QFile f(file);
        QVERIFY(f.open(QIODevice::ReadOnly));
        QJsonParseError err{};
        auto doc = QJsonDocument::fromJson(f.readAll(), &err);
        QCOMPARE(err.error, QJsonParseError::NoError);
        QVERIFY(doc.isArray());
        QCOMPARE(doc.array().size(), qsizetype(1));
        // written via a temp file + rename: no leftover temp files next to it
        QCOMPARE(QDir(dir.path()).entryList(QDir::Files), QStringList{ "sessions.json" });
    }
};

QTEST_GUILESS_MAIN(StatsStoreTest)

#include "StatsStore_test.moc"
