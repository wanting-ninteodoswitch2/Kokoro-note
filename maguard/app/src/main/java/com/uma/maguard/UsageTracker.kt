package com.uma.maguard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.util.Calendar

/**
 * UsageStatsManager を使って「実際の使用時間」を計測する。
 *
 * ＜なぜ Handler.postDelayed から変えたのか＞
 * 前の実装は「開いてから n 分後」をタイマーで測っていたため、
 *  ・画面を消している間もカウントが進む
 *  ・一度アプリを離れると計測がリセットされる（離脱→再開で永久に到達しない）
 *  ・Dozeモードでタイマー自体が遅延する
 * という問題があった。
 *
 * UsageStatsManager は OS が記録している実際の利用ログを読むので、
 * 「今日そのアプリを合計何分使ったか」「連続して何分開いているか」を
 * 正確に取得できる。細切れに何度も開き直す使い方にも対応できる。
 *
 * この API には PACKAGE_USAGE_STATS 権限が必要で、
 * 通常の権限ダイアログではなく専用の設定画面での許可が要る。
 */
class UsageTracker(private val context: Context) {

    /**
     * 権限が付与されているか。
     *
     * unsafeCheckOpNoThrow は API 29 で追加されたメソッドで、
     * それ以前は checkOpNoThrow（現在は非推奨）を使う必要がある。
     * minSdk が 26 なので、分岐しないと Android 8/9 で
     * NoSuchMethodError になる。
     */
    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 今日の使用時間・起動回数・現在のセッション時間を、一度の走査でまとめて返す。
     *
     * ＜なぜまとめるのか＞
     * 以前はこの3つを別々のメソッドで取っており、それぞれが
     * queryEvents を呼んで同じイベント列を頭から走査していた。
     * つまり同じ仕事を3回していたことになる。
     *
     * この集計はアクセシビリティサービスから、しかも
     * 「対象アプリを開いたまさにその瞬間」にメインスレッドで走る。
     * 利用履歴が溜まっている端末ほど重くなり、
     * オーバーレイの表示が遅れたり、端末全体がもたついたりする。
     *
     * 走査は1回で済むので、まとめる。
     */
    fun getStats(packageName: String): Stats {
        if (!hasPermission()) return Stats(0, 0, 0)

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val dayStart = startOfToday()
        // セッションの開始点は当日より前にあり得るので、窓は24時間で取る
        val windowStart = now - LONG_LOOKBACK_MS

        val events = usm.queryEvents(windowStart, now)
        val event = UsageEvents.Event()

        // 今日の使用時間
        var todayMs = 0L
        var foregroundSince = 0L

        // 今日の起動回数
        var launchCount = 0
        var lastBackgroundForCount = -1L

        // 現在のセッション
        var sessionStartedAt = 0L
        var lastBackgroundAt = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != packageName) continue
            val t = event.timeStamp

            if (event.eventType == foregroundEventType) {
                foregroundSince = t

                // 起動回数：短い往復は同じ起動の続きとみなす
                if (t >= dayStart) {
                    val gap = if (lastBackgroundForCount < 0) Long.MAX_VALUE
                              else t - lastBackgroundForCount
                    if (gap > GAP_TOLERANCE_MS) launchCount++
                }

                // セッション：短い中断はまたぎ越して起点を保つ
                val sessionGap = t - lastBackgroundAt
                if (sessionStartedAt == 0L || lastBackgroundAt == 0L ||
                    sessionGap > GAP_TOLERANCE_MS
                ) {
                    sessionStartedAt = t
                }
                lastBackgroundAt = 0L

            } else if (event.eventType == backgroundEventType) {
                // 今日の使用時間：当日にかかる部分だけを足す
                if (foregroundSince > 0) {
                    val from = maxOf(foregroundSince, dayStart)
                    if (t > from) todayMs += t - from
                    foregroundSince = 0
                }
                lastBackgroundForCount = t
                lastBackgroundAt = t
            }
        }

        // まだ前面にいる分を加算する
        if (foregroundSince > 0) {
            val from = maxOf(foregroundSince, dayStart)
            if (now > from) todayMs += now - from
        }

        // 開始点が窓の外にある長時間セッションの救済
        val sessionMinutes = if (sessionStartedAt == 0L) {
            if (isCurrentlyForeground(packageName)) (LONG_LOOKBACK_MS / 60_000L).toInt() else 0
        } else {
            val stillActive = lastBackgroundAt == 0L ||
                (now - lastBackgroundAt) <= GAP_TOLERANCE_MS
            if (stillActive) ((now - sessionStartedAt) / 60_000L).toInt() else 0
        }

        return Stats(
            todayMinutes = (todayMs / 60_000L).toInt(),
            todayLaunches = launchCount,
            sessionMinutes = sessionMinutes
        )
    }

    data class Stats(
        val todayMinutes: Int,
        val todayLaunches: Int,
        val sessionMinutes: Int
    )

    /**
     * 指定アプリを「今日」合計で何分使ったかを返す。
     *
     * queryEvents で MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND のペアを拾い、
     * その差分を足し合わせる。queryUsageStats より正確で、
     * 画面オフ中の時間を含めずに済む。
     */
    fun getTodayForegroundMinutes(packageName: String): Int {
        if (!hasPermission()) return 0

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = startOfToday()
        val now = System.currentTimeMillis()

        val events = usm.queryEvents(start, now)
        val event = UsageEvents.Event()

        var totalMs = 0L
        var lastForegroundAt = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != packageName) continue

            // when の分岐に変数は使えない（定数式が必要）ため if で書く
            if (event.eventType == foregroundEventType) {
                lastForegroundAt = event.timeStamp
            } else if (event.eventType == backgroundEventType) {
                if (lastForegroundAt > 0) {
                    totalMs += event.timeStamp - lastForegroundAt
                    lastForegroundAt = 0
                }
            }
        }

        // まだ前面にいる場合は、現在時刻までを加算する
        if (lastForegroundAt > 0) {
            totalMs += now - lastForegroundAt
        }

        return (totalMs / 60_000L).toInt()
    }

    /**
     * 指定アプリを「今日」何回開いたかを返す。
     * 起動回数の上限機能で使う。
     *
     * 通知バーを一瞬開く程度の中断は同じ起動の続きとして扱い、
     * GAP_TOLERANCE_MS を超えて離れていた場合だけ新しい起動として数える。
     * これがないと、アプリを開いたまま通知を数回確認しただけで
     * 起動回数の上限にすぐ達してしまう。
     */
    fun getTodayLaunchCount(packageName: String): Int {
        if (!hasPermission()) return 0

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(startOfToday(), System.currentTimeMillis())
        val event = UsageEvents.Event()

        var count = 0
        var lastBackgroundAt = -1L   // -1 = まだ一度も前面に来ていない

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != packageName) continue

            if (event.eventType == foregroundEventType) {
                val gap = if (lastBackgroundAt < 0) Long.MAX_VALUE else event.timeStamp - lastBackgroundAt
                if (gap > GAP_TOLERANCE_MS) count++
            } else if (event.eventType == backgroundEventType) {
                lastBackgroundAt = event.timeStamp
            }
        }
        return count
    }

    /**
     * いま、そのアプリが前面にいるか。
     *
     * セッション時間の計算とは独立して判定できるようにしてある。
     * 「開始点が見つからない」ことと「使っていない」ことは別物で、
     * これを混同すると長時間利用を取りこぼす。
     *
     * ＜窓を段階的に広げる理由＞
     * 全アプリのイベントを追い、最後に前面へ来たのがどのパッケージかを見る。
     * このとき窓が短すぎると、長時間ずっと前面にいるアプリを
     * 検出できない。前面イベントは「切り替わった瞬間」にしか
     * 記録されないため、10分間ずっと同じアプリを見ていると
     * 直近10分の窓には前面イベントが1件も存在しないからだ。
     *
     * そこでまず短い窓で探し、1件も見つからなければ窓を広げる。
     * 通常のアプリ切り替えは短い窓で解決するので、
     * 重い24時間走査は長時間セッションのときだけに限定できる。
     */
    fun isCurrentlyForeground(packageName: String): Boolean {
        if (!hasPermission()) return false

        lastForegroundPackageWithin(SHORT_LOOKBACK_MS)?.let { return it == packageName }
        // 短い窓に前面イベントが1件も無い＝誰かがずっと前面にいる。窓を広げて確認する
        return lastForegroundPackageWithin(LONG_LOOKBACK_MS) == packageName
    }

    /**
     * 指定した時間内で、最後に前面へ来たパッケージ名。
     * 前面イベントが1件も無ければ null を返す（「該当なし」と区別するため）。
     */
    private fun lastForegroundPackageWithin(lookbackMs: Long): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - lookbackMs, now)
        val event = UsageEvents.Event()

        var lastForegroundPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == foregroundEventType) {
                lastForegroundPackage = event.packageName
            }
        }
        return lastForegroundPackage
    }

    /**
     * いま何分続けて開いているか。
     *
     * ＜長時間利用の取りこぼしについて＞
     * 以前はルックバックを6時間に固定していたため、6時間以上
     * ぶっ通しで使っているとセッションの開始イベントが窓の外に出てしまい、
     * 「開始点が見つからない＝使っていない」と誤判定して 0 を返していた。
     *
     * その結果、UsageAlarmReceiver が「すでに離れている」と判断し、
     * このアプリが最も介入すべき相手である長時間利用者に対して
     * 一切介入しない、という本末転倒な状態になっていた。
     *
     * 対策は2段構え：
     *  1. ルックバックを24時間に広げる
     *  2. それでも開始点が見つからず、かつ現在前面にいる場合は、
     *     「少なくとも窓の長さだけは使っている」とみなす
     *
     * ＜短い中断の扱い＞
     * 通知バーを一瞬開くなどでも前面/背面のイベントは記録される。
     * これを離脱として扱うと連続使用のカウントが頻繁にリセットされ、
     * 「n分で再確認」がほぼ発動しなくなるため、
     * GAP_TOLERANCE_MS 以内の復帰は同じセッションの続きとみなす。
     */
    fun getCurrentSessionMinutes(packageName: String): Int {
        if (!hasPermission()) return 0

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val windowStart = now - LONG_LOOKBACK_MS
        val events = usm.queryEvents(windowStart, now)
        val event = UsageEvents.Event()

        var sessionStartedAt = 0L   // このセッションが実際に始まった時刻
        var lastBackgroundAt = 0L   // 直近で背面に回った時刻（0なら今も前面）

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != packageName) continue

            if (event.eventType == foregroundEventType) {
                val gap = event.timeStamp - lastBackgroundAt
                if (sessionStartedAt == 0L || lastBackgroundAt == 0L || gap > GAP_TOLERANCE_MS) {
                    // 前回離脱してから間が空いている＝新しいセッションの開始
                    sessionStartedAt = event.timeStamp
                }
                // 間が短ければ sessionStartedAt はそのまま保持し、続きとして扱う
                lastBackgroundAt = 0L
            } else if (event.eventType == backgroundEventType) {
                lastBackgroundAt = event.timeStamp
            }
        }

        // 開始点が窓の外にある長時間セッション。
        //
        // sessionStartedAt == 0 は「この窓の中に前面イベントが無かった」
        // という意味でしかない。使っていないのか、窓より前から
        // ずっと使い続けているのかは、これだけでは区別できない。
        //
        // 以前はここで sawAnyEvent（何らかのイベントがあったか）も
        // 条件にしていたが、前面/背面以外のイベントが1件でもあると
        // フォールバックが阻害されてしまい、長時間利用を取りこぼしていた。
        // 現在前面にいるかどうかだけで判断する。
        if (sessionStartedAt == 0L) {
            return if (isCurrentlyForeground(packageName)) {
                (LONG_LOOKBACK_MS / 60_000L).toInt()
            } else {
                0
            }
        }

        // 最後の中断から許容時間を超えて戻ってきていなければ、
        // 「今も実質的に使用中」とみなす
        val stillActive = lastBackgroundAt == 0L || (now - lastBackgroundAt) <= GAP_TOLERANCE_MS
        if (!stillActive) return 0

        return ((now - sessionStartedAt) / 60_000L).toInt()
    }

    /**
     * 前面に来たことを示すイベント種別。
     *
     * API 29 で ACTIVITY_RESUMED / ACTIVITY_PAUSED が追加され、
     * 従来の MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND は非推奨になった。
     * 新しい方はマルチウィンドウや一部のメーカー独自ROMでの
     * 取りこぼしが少ないため、使える環境では優先する。
     *
     * なお両者の定数値は同じ（1と2）なので実際には互換性があるが、
     * 意図を明示するために分岐させている。
     */
    private val foregroundEventType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }

    private val backgroundEventType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_PAUSED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_BACKGROUND
        }

    companion object {
        // 通知バーの開閉や着信確認などの一瞬の中断を許容する猶予
        private const val GAP_TOLERANCE_MS = 90_000L

        // セッションの開始点を探す窓。長時間利用を取りこぼさないよう
        // 24時間まで遡る。日をまたぐセッションもここでカバーできる。
        private const val LONG_LOOKBACK_MS = 24 * 60 * 60 * 1000L

        // 「いま前面にいるか」の判定は直近だけ見れば足りる
        private const val SHORT_LOOKBACK_MS = 10 * 60 * 1000L
    }

    private fun startOfToday(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
