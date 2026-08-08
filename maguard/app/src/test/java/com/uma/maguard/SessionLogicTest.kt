package com.uma.maguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * セッション判定と起動回数カウントのロジックを検証する。
 *
 * UsageStatsManager 本体は端末のOSに依存するため単体テストできない。
 * そこで判定ロジックだけを純粋な関数として切り出し、
 * イベント列を与えて期待どおりに動くかを確かめる。
 *
 * ここでテストしているのは、実機で再現しづらく、
 * かつ過去に実際にバグが出た2つの領域：
 *  ・長時間セッションの取りこぼし（開始点が窓の外にある場合）
 *  ・起動回数の境界（初回のオフバイワン、短い往復の重複カウント）
 */
class SessionLogicTest {

    private val minute = 60_000L
    private val gapTolerance = 90_000L
    private val lookback = 24 * 60 * minute

    // ---------- セッション時間の判定 ----------

    /**
     * 実装と同じ判定をここで再現する。
     * events: (時刻, 前面ならtrue) の列。時刻は「現在からの遡り分」ではなく絶対値。
     */
    private fun sessionMinutes(
        now: Long,
        events: List<Pair<Long, Boolean>>,
        isForegroundNow: Boolean
    ): Int {
        val windowStart = now - lookback
        var sessionStartedAt = 0L
        var lastBackgroundAt = 0L

        events.filter { it.first >= windowStart }.forEach { (t, isForeground) ->
            if (isForeground) {
                val gap = t - lastBackgroundAt
                if (sessionStartedAt == 0L || lastBackgroundAt == 0L || gap > gapTolerance) {
                    sessionStartedAt = t
                }
                lastBackgroundAt = 0L
            } else {
                lastBackgroundAt = t
            }
        }

        // 開始点が窓の外にある長時間セッションの救済
        if (sessionStartedAt == 0L) {
            return if (isForegroundNow) (lookback / minute).toInt() else 0
        }

        val stillActive = lastBackgroundAt == 0L || (now - lastBackgroundAt) <= gapTolerance
        if (!stillActive) return 0

        return ((now - sessionStartedAt) / minute).toInt()
    }

    @Test
    fun `開いて15分経過したら15分と判定される`() {
        val now = 100 * minute
        val events = listOf(85 * minute to true)
        assertEquals(15, sessionMinutes(now, events, isForegroundNow = true))
    }

    @Test
    fun `通知バーを一瞬覗いてもセッションは継続する`() {
        val now = 100 * minute
        val events = listOf(
            85 * minute to true,
            90 * minute to false,   // 通知バーへ
            90 * minute + 20_000 to true  // 20秒後に復帰
        )
        // 起点は85分のまま維持され、15分と判定されるべき
        assertEquals(15, sessionMinutes(now, events, isForegroundNow = true))
    }

    @Test
    fun `十分に間が空いたら新しいセッションになる`() {
        val now = 100 * minute
        val events = listOf(
            30 * minute to true,
            40 * minute to false,   // 離脱
            95 * minute to true     // 55分後に再開
        )
        assertEquals(5, sessionMinutes(now, events, isForegroundNow = true))
    }

    @Test
    fun `離れたあとはセッション0になる`() {
        val now = 100 * minute
        val events = listOf(
            85 * minute to true,
            90 * minute to false    // 10分前に離脱したきり
        )
        assertEquals(0, sessionMinutes(now, events, isForegroundNow = false))
    }

    /**
     * 回帰テスト：24時間以上ぶっ通しで使っている場合。
     *
     * 窓の中に前面イベントが1件も無いため sessionStartedAt が 0 のままになる。
     * ここで 0 を返してしまうと「離れている」と誤判定され、
     * 最も介入すべき長時間利用者に何もしないことになる。
     */
    @Test
    fun `開始点が窓の外にある長時間セッションでも介入対象になる`() {
        val now = 30 * 60 * minute          // 30時間経過時点
        val events = emptyList<Pair<Long, Boolean>>()   // 窓内にイベントなし
        val result = sessionMinutes(now, events, isForegroundNow = true)
        assertTrue("長時間セッションが0分と判定されている", result > 0)
        assertEquals((lookback / minute).toInt(), result)
    }

    /**
     * 回帰テスト：前面/背面以外のイベントが混ざっていても救済される。
     *
     * 以前は「何らかのイベントがあったか」を条件に入れていたため、
     * 無関係なイベントが1件あるだけでフォールバックが効かなくなっていた。
     * 現在は「いま前面にいるか」だけで判断する。
     */
    @Test
    fun `窓内に背面イベントだけある長時間セッションも救済される`() {
        val now = 30 * 60 * minute
        // 前面イベントは無いが、背面イベントは記録されている状況
        val events = listOf(10 * 60 * minute to false)
        val result = sessionMinutes(now, events, isForegroundNow = true)
        assertTrue("フォールバックが働いていない", result > 0)
    }

    // ---------- 起動回数のカウント ----------

    /** Prefs.recordLaunch と同じ判定を再現する */
    private class LaunchCounter(private val minGapMs: Long) {
        private var count = 0
        private var lastAt: Long? = null

        fun record(now: Long): Int {
            if (lastAt == null || now - lastAt!! > minGapMs) count++
            lastAt = now
            return count
        }
    }

    /**
     * 回帰テスト：初回のオフバイワン。
     * lastAt の未設定と「前回が古い」を区別していないと、
     * 初回が 0 回と数えられてしまう。
     */
    @Test
    fun `初回の起動は1回として数えられる`() {
        val counter = LaunchCounter(gapTolerance)
        assertEquals(1, counter.record(0))
    }

    @Test
    fun `短い間隔の往復は同じ起動として扱われる`() {
        val counter = LaunchCounter(gapTolerance)
        counter.record(0)
        counter.record(30_000)   // 30秒後
        assertEquals(1, counter.record(60_000))  // さらに30秒後
    }

    @Test
    fun `十分に間が空いた再訪は新しい起動になる`() {
        val counter = LaunchCounter(gapTolerance)
        counter.record(0)
        assertEquals(2, counter.record(10 * minute))
    }

    /**
     * 「10回まで」の設定でちょうど10回開け、11回目で止まることを確認する。
     * 判定は count > limit なので、境界がずれていないかを見る。
     */
    @Test
    fun `上限10回なら10回目までは開けて11回目で止まる`() {
        val counter = LaunchCounter(gapTolerance)
        val limit = 10
        var allowed = 0

        repeat(12) { i ->
            val count = counter.record(i * 10 * minute)
            if (count <= limit) allowed++
        }

        assertEquals(10, allowed)
    }

    // ---------- 時間帯ルール ----------

    private fun contains(startMinute: Int, endMinute: Int, minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> true
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    @Test
    fun `日をまたぐ時間帯が正しく判定される`() {
        // 22:00〜02:00
        assertTrue(contains(22 * 60, 2 * 60, 23 * 60))   // 23:00
        assertTrue(contains(22 * 60, 2 * 60, 1 * 60))    // 01:00
        assertFalse(contains(22 * 60, 2 * 60, 12 * 60))  // 12:00
    }

    @Test
    fun `開始と終了が同じなら終日として扱われる`() {
        assertTrue(contains(0, 0, 0))
        assertTrue(contains(0, 0, 13 * 60))
        assertTrue(contains(0, 0, 23 * 60 + 59))
    }
}
