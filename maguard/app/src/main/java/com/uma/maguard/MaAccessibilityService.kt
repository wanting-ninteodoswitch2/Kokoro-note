package com.uma.maguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 前面アプリを検知して介入を判断する、アプリの中核。
 *
 * ＜介入のトリガー＞
 * 1. 起動時          … 対象アプリを開いた瞬間
 * 2. 連続使用の超過  … 開いたまま n 分経過（AlarmManagerで予約）
 * 3. 1日の総量超過  … 今日の合計使用時間 / 起動回数が上限を超えた
 *
 * 2と3は UsageStatsManager の実測値を使う。タイマーの経過時間ではなく
 * OSが記録した実際の利用ログを見るので、アプリを行き来しても
 * 累積が正しく積み上がる。
 */
class MaAccessibilityService : AccessibilityService() {

    private var currentPackage: String? = null
    private val tracker by lazy { UsageTracker(this) }

    companion object {
        private const val STATS_CACHE_MS = 10_000L

        // この時間内に前面へ戻った場合は、同じ起動の続きとみなす。
        // UsageTracker 側のセッション判定と揃えている。
        private const val LAUNCH_GAP_MS = 90_000L

        /**
         * 猶予期限は Prefs に永続化する。
         * 以前はメモリ上の Map だけで持っていたため、OSにプロセスを
         * 落とされると猶予が消え、アプリに戻った瞬間にまた介入が出ていた。
         */
        fun grantGrace(context: Context, packageName: String, minutes: Int) {
            Prefs(context).setGraceUntil(
                packageName,
                System.currentTimeMillis() + minutes * 60_000L
            )
        }

        fun clearGrace(context: Context, packageName: String) {
            Prefs(context).clearGrace(packageName)
        }

        fun isEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${MaAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return

        if (packageName == this.packageName) return

        // 通知バーやクイック設定は「別のアプリに移った」とは扱わない。
        // ここで currentPackage を更新してしまうと、通知を一瞬覗いて
        // 戻っただけで新しい起動として数えられ、起動回数が水増しされる。
        //
        // ホーム画面（ランチャー）は別パッケージなので、この除外には
        // 該当せず、通常どおり切り替えとして扱われる。
        // アプリ → ホーム → アプリ は、意図どおり新しい起動になる。
        if (packageName == "com.android.systemui") return

        // 別アプリに移ったら、前のアプリの使用時間チェック予約を取り消す
        val previous = currentPackage
        // 対象アプリの内部で画面が切り替わる（別のアクティビティ/フラグメントに
        // 移る）だけでも TYPE_WINDOW_STATE_CHANGED は何度も飛んでくる。
        // 「本当にこのアプリに入ってきた瞬間」だけを新しい起動として扱うため、
        // 直前の前面アプリと変わっていないかをここで確定させておく。
        // これを使わずに毎回オーバーレイを出していたため、アプリ内を
        // 操作しているだけで「まだ見たい？」が何度も出てしまっていた
        // （実機で「1分ごとに出てくる」と報告された不具合）。
        val isFreshEntry = packageName != previous
        if (isFreshEntry) {
            previous?.let { UsageAlarmScheduler.cancel(this, it) }
            currentPackage = packageName
        }

        val prefs = Prefs(this)
        val config = prefs.getEffectiveConfig(packageName)

        if (config == null) {
            // 対象外のアプリに移った（ホーム画面、設定など）。
            //
            // 以前はここで単に return していたため、直前に出していた
            // オーバーレイが画面に残り、戻るキーを押すまで
            // 関係のないアプリの操作まで妨害し続けていた。
            //
            // ただしこの分岐は、日常のアプリ操作のたびに通過する
            // 最も頻繁に実行される経路でもある。ここで重い処理や
            // サービス起動を行うと端末全体の動作に影響するため、
            // 実際にオーバーレイが出ているときだけ閉じる。
            if (OverlayService.isShowing) {
                // 画面分割などで、対象アプリが画面に残ったまま
                // 別アプリにフォーカスが移ることがある。
                // その状態で閉じると介入をすり抜けられるため、
                // 対象アプリがまだ前面扱いなら維持する。
                val target = OverlayService.showingTarget
                val stillThere = target != null &&
                    tracker.hasPermission() &&
                    tracker.isCurrentlyForeground(target)
                OverlayService.dismiss(stillForeground = stillThere)
            }
            return
        }

        // 対象アプリが前面に来た瞬間に、自前で起動回数を数える。
        // UsageStats の反映を待つと境界が1回ぶんずれるため、
        // 検知した時点で確定させる。
        var ownLaunchCount: Int? = null
        if (isFreshEntry) {
            ownLaunchCount = prefs.recordLaunch(packageName, todayKey(), LAUNCH_GAP_MS)

            // ここでキャッシュを丸ごと捨てると、直後の集計で
            // 24時間分のイベント走査が「アプリを開いたまさにその瞬間」に走る。
            // 表示が遅れる原因になるので、変わった値だけを差し替える。
            //
            // 新しい起動なのでセッションは0から始まる。
            // 今日の合計使用時間は、キャッシュの有効期間（数秒）では
            // ほとんど変わらないため、そのまま使って差し支えない。
            statsCache[packageName]?.let { (at, cached) ->
                statsCache[packageName] = at to cached.copy(
                    sessionMinutes = 0,
                    todayLaunches = maxOf(cached.todayLaunches, ownLaunchCount)
                )
            }
        }

        val now = System.currentTimeMillis()
        val inGrace = now < prefs.getGraceUntil(packageName)

        // 1日の上限チェックは猶予より優先する。
        // 「もう少しだけ」を繰り返して無限に延長できてしまうのを防ぐため。
        //
        // ここも isFreshEntry で絞る。ブロック中に何らかの理由で
        // アプリ内に留まり続けた場合、内部の画面遷移のたびに
        // ブロック画面を出し直す必要はない（次に本当に入り直したときに
        // また出せば十分）。
        val limitBreach = checkDailyLimits(packageName, config)
        if (limitBreach != null) {
            if (isFreshEntry) {
                showOverlay(packageName, OverlayService.MODE_DAILY_LIMIT, limitBreach)
            }
            return
        }

        if (inGrace) {
            // 猶予中は通さつつ、連続使用のチェックだけ予約しておく
            scheduleUsageCheck(packageName, config)
            return
        }

        // 起動時の「ひと呼吸」は、本当にこのアプリへ入ってきたときだけ出す。
        // 同じアプリ内で画面を操作しているだけなら何もしない
        // （連続使用のチェックは usageLimitMin 側のアラームに任せる）。
        if (isFreshEntry) {
            showOverlay(packageName, OverlayService.MODE_LAUNCH, currentStats(packageName))
        }
    }

    /**
     * 1日の上限に達しているかを確認する。
     * 達していれば、表示用の統計を返す。
     */
    private fun checkDailyLimits(packageName: String, config: Prefs.AppConfig): Stats? {
        if (!tracker.hasPermission()) return null
        val stats = currentStats(packageName)

        // 時間は「到達したら」ブロック（60分設定なら60分で止める）
        val overTime = config.dailyLimitMin > 0 && stats.todayMinutes >= config.dailyLimitMin

        // 回数は「超えたら」ブロック。
        // todayLaunches は自前カウントを含むため、いま開いた回も確実に
        // 数えられている。10回設定なら、11回目に 11 > 10 が成立して止まり、
        // 10回までは開ける（UIの「10回まで」と一致する）。
        val overCount = config.dailyLaunchLimit > 0 && stats.todayLaunches > config.dailyLaunchLimit

        return if (overTime || overCount) stats else null
    }

    // 画面が切り替わるたびに1日分の利用ログを走査すると重く、
    // 端末によっては操作がもたつく。短時間はキャッシュを使い回す。
    //
    // 以前は「直近1件」だけを保持していたため、アプリAとBを
    // 行き来するとキャッシュが毎回捨てられ、ほとんど効いていなかった。
    // パッケージ単位で保持するよう変更している。
    private val statsCache = mutableMapOf<String, Pair<Long, Stats>>()

    private fun currentStats(packageName: String): Stats {
        val now = System.currentTimeMillis()
        statsCache[packageName]?.let { (at, stats) ->
            if (now - at < STATS_CACHE_MS) return stats
        }

        // 使用時間・起動回数・セッションを一度の走査でまとめて取る。
        // 個別に呼ぶと同じイベント列を3回走査することになる。
        val measured = tracker.getStats(packageName)

        // 起動回数は、自前カウントと UsageStats の大きい方を採る。
        // 自前カウント: 今この瞬間の起動まで確実に含む（境界が正確）
        // UsageStats:   このアプリを止めていた間の起動も拾える
        val ownCount = Prefs(this).getLaunchCount(packageName, todayKey())

        val stats = Stats(
            sessionMinutes = measured.sessionMinutes,
            todayMinutes = measured.todayMinutes,
            todayLaunches = maxOf(ownCount, measured.todayLaunches)
        )
        statsCache[packageName] = now to stats
        return stats
    }

    /**
     * 連続使用のチェックを予約する。
     * すでに session が進んでいる場合は、残り時間だけ待つ。
     */
    private fun scheduleUsageCheck(packageName: String, config: Prefs.AppConfig) {
        if (config.usageLimitMin <= 0) return

        val alreadyUsed = if (tracker.hasPermission()) {
            tracker.getCurrentSessionMinutes(packageName)
        } else 0

        // すでに閾値を超えている場合は、受信側が即座に介入できるよう1分後に鳴らす
        UsageAlarmScheduler.schedule(
            context = this,
            packageName = packageName,
            delayMinutes = (config.usageLimitMin - alreadyUsed).coerceAtLeast(1),
            thresholdMinutes = config.usageLimitMin
        )
    }

    private fun showOverlay(packageName: String, mode: String, stats: Stats) {
        UsageAlarmScheduler.cancel(this, packageName)
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_TARGET_PACKAGE, packageName)
            putExtra(OverlayService.EXTRA_MODE, mode)
            putExtra(OverlayService.EXTRA_SESSION_MINUTES, stats.sessionMinutes)
            putExtra(OverlayService.EXTRA_TODAY_MINUTES, stats.todayMinutes)
            putExtra(OverlayService.EXTRA_TODAY_LAUNCHES, stats.todayLaunches)
        }
        // フォアグラウンドサービスとして起動する。
        // 通常の startService はバックグラウンドから呼ぶと例外になる。
        //
        // SYSTEM_ALERT_WINDOW を持っていればバックグラウンド起動制限は
        // 免除されるが、ユーザーが設定から権限を外した直後などに
        // ForegroundServiceStartNotAllowedException が飛ぶことがある。
        // 介入できないのは残念だが、そのために端末操作中の
        // アプリごと落とすわけにはいかない。
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // 表示できなかった場合は何もしない
        }
    }

    private fun todayKey(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.JAPAN)
            .format(java.util.Date())

    data class Stats(
        val sessionMinutes: Int,
        val todayMinutes: Int,
        val todayLaunches: Int
    )

    override fun onInterrupt() {}
}
