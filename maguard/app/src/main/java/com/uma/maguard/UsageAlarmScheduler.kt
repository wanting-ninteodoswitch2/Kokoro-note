package com.uma.maguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 連続使用のチェックを AlarmManager で予約する。
 *
 * ＜2つの時間の役割を混同しない＞
 * このアプリには紛らわしい2つの「分」がある。
 *
 *  graceMinutes  … 「開く瞬間の介入」を抑える時間。
 *                   見ると決めた直後に何度も同じ画面が出ないようにするためのもの。
 *  usageLimitMin … 「これだけ続けて使ったら声をかける」時間。
 *
 * 以前はこの2つを取り違えており、猶予5分・上限15分の設定でも
 * 5分で再確認が出ていた。ここを明確に分けるため、
 * アラームには「何分後に鳴らすか（delayMinutes）」だけでなく
 * 「セッションが何分に達したら介入すべきか（thresholdMinutes）」も持たせる。
 *
 * ＜なぜ Handler ではなく AlarmManager なのか＞
 * Handler.postDelayed はプロセスが生きている前提の仕組みで、
 * Doze やバックグラウンド制限で簡単に遅延・消滅する。
 * setExactAndAllowWhileIdle なら Doze 中でも指定時刻に起動できる。
 */
object UsageAlarmScheduler {

    private const val ACTION_CHECK_USAGE = "com.uma.maguard.CHECK_USAGE"
    private const val EXTRA_PACKAGE = "package"
    private const val EXTRA_THRESHOLD = "threshold_minutes"

    /**
     * @param delayMinutes     何分後にチェックするか
     * @param thresholdMinutes そのとき、セッションが何分に達していたら介入するか
     */
    fun schedule(context: Context, packageName: String, delayMinutes: Int, thresholdMinutes: Int) {
        if (delayMinutes <= 0 || thresholdMinutes <= 0) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMinutes * 60_000L
        val pi = buildPendingIntent(context, packageName, thresholdMinutes)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 正確なアラームが許可されていないので、多少ずれても動く方式にする
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context, packageName: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildPendingIntent(context, packageName, 0))
    }

    /**
     * PendingIntent の同一性は component / action / data / categories で決まり、
     * extras は無視される。requestCode が全パッケージで共通だと
     * アプリAの予約がBに上書きされてしまうため、Prefs 側で採番した
     * 重複しないIDを使う。
     *
     * 同じ理由で、cancel 時は threshold の値が違っても同じ
     * PendingIntent として扱われるので、取り消しは正しく効く。
     */
    private fun buildPendingIntent(
        context: Context,
        packageName: String,
        thresholdMinutes: Int
    ): PendingIntent {
        val intent = Intent(context, UsageAlarmReceiver::class.java).apply {
            action = ACTION_CHECK_USAGE
            putExtra(EXTRA_PACKAGE, packageName)
            putExtra(EXTRA_THRESHOLD, thresholdMinutes)
        }
        return PendingIntent.getBroadcast(
            context,
            Prefs(context).getAlarmId(packageName),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * アラーム発火時に呼ばれる受信機。
     *
     * ここで実測値を取り直し、本当に閾値に達しているかを確認してから介入する。
     * 到達していなければ、残り時間で予約し直す。
     * これがないと「15分で再確認」の設定が守られない。
     */
    class UsageAlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return
            val threshold = intent.getIntExtra(EXTRA_THRESHOLD, 0)
            if (threshold <= 0) return

            val config = Prefs(context).getEffectiveConfig(packageName) ?: return
            if (config.usageLimitMin <= 0) return

            val tracker = UsageTracker(context)

            // すでにアプリを離れているなら介入は不要。
            // ここは「セッションが0分＝離脱」と決めつけず、前面判定で確かめる。
            // 計測の都合でセッションが取れないだけの場合に
            // 介入を取りこぼすのを避けるため。
            if (!tracker.isCurrentlyForeground(packageName)) return

            val sessionMinutes = tracker.getCurrentSessionMinutes(packageName)

            // まだ閾値に届いていない（アラームが早く鳴った / 途中で離席していた）。
            // 残り時間で予約し直し、設定した時間を必ず守る。
            if (sessionMinutes < threshold) {
                schedule(
                    context,
                    packageName,
                    delayMinutes = (threshold - sessionMinutes).coerceAtLeast(1),
                    thresholdMinutes = threshold
                )
                return
            }

            val todayMinutes = tracker.getTodayForegroundMinutes(packageName)

            val overlayIntent = Intent(context, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_TARGET_PACKAGE, packageName)
                putExtra(OverlayService.EXTRA_MODE, OverlayService.MODE_USAGE)
                putExtra(OverlayService.EXTRA_SESSION_MINUTES, sessionMinutes)
                putExtra(OverlayService.EXTRA_TODAY_MINUTES, todayMinutes)
            }
            // アラーム経由＝完全なバックグラウンドからの起動になる。
            // SYSTEM_ALERT_WINDOW による免除が効かない状況
            // （権限を外された直後など）では例外が飛ぶため保護する。
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(overlayIntent)
                } else {
                    context.startService(overlayIntent)
                }
            } catch (e: Exception) {
                // 表示できなかった場合は次の起動時に改めて介入する
            }
        }
    }
}
