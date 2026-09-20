#include <QDateTime>
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
};

QTEST_GUILESS_MAIN(StatsStoreTest)

#include "StatsStore_test.moc"
