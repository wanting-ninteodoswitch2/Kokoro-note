package com.uma.maguard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ホーム画面ウィジェット。
 *
 * ＜なぜウィジェットが効くのか＞
 * オーバーレイによる介入は「開こうとした後」に働く。
 * つまり、手が動いてしまった後の介入でしかない。
 *
 * ウィジェットは「開く前」——ホーム画面を見た瞬間に数字が目に入る。
 * アイコンをタップしようとする手前の段階で
 * 「今日すでに1時間20分」という事実が視界に入ることで、
 * そもそも手が伸びなくなることを狙う。
 *
 * 更新は30分ごと（updatePeriodMillisの最小値）に加え、
 * 介入が起きたタイミングでも明示的に更新する。
 */
class UsageWidgetProvider : AppWidgetProvider() {

    /**
     * ＜バックグラウンドで集計する理由＞
     * AppWidgetProvider のコールバックはメインスレッドで実行される。
     * ここでは対象アプリごとに UsageStatsManager へ問い合わせ、
     * 最大24時間分のイベントを走査するため、対象が増えるほど重くなる。
     * メインスレッドでやると、最悪 ANR（応答なし）を招く。
     *
     * goAsync() でブロードキャストの寿命を延ばし、
     * 別スレッドで集計してから表示を更新する。
     * finish() を必ず呼ばないとシステムに怒られるので、
     * 例外が出ても通るよう finally に置いている。
     */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        Thread {
            try {
                val snapshot = collectSnapshot(context)
                appWidgetIds.forEach { id ->
                    appWidgetManager.updateAppWidget(id, buildViews(context, snapshot))
                }
            } catch (e: Exception) {
                // 集計に失敗しても、ウィジェットが壊れるだけで
                // アプリ本体の動作には影響しないので握りつぶす
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    /** 表示に必要な数値を一度にまとめて取る */
    private data class Snapshot(
        val totalMinutes: Int,
        val totalLaunches: Int,
        val resisted: Int,
        val streak: Int,
        val dailyLimit: Int,
        val hasPermission: Boolean
    )

    /** 重い集計処理。バックグラウンドスレッドから呼ぶこと */
    private fun collectSnapshot(context: Context): Snapshot {
        val prefs = Prefs(context)
        val tracker = UsageTracker(context)
        val targets = prefs.getTargets()

        var totalMinutes = 0
        var totalLaunches = 0
        val hasPermission = tracker.hasPermission()

        if (hasPermission) {
            targets.keys.forEach { pkg ->
                totalMinutes += tracker.getTodayForegroundMinutes(pkg)
                totalLaunches += tracker.getTodayLaunchCount(pkg)
            }
        }

        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN)
        val todayKey = fmt.format(Date())
        val cal = Calendar.getInstance()
        val previousKeys = (1..60).map {
            cal.add(Calendar.DAY_OF_YEAR, -1)
            fmt.format(cal.time)
        }

        // 上限が設定されているもののうち、最も厳しい値を基準にする
        val dailyLimit = targets.values
            .filter { it.dailyLimitMin > 0 }
            .minOfOrNull { it.dailyLimitMin } ?: 0

        return Snapshot(
            totalMinutes = totalMinutes,
            totalLaunches = totalLaunches,
            resisted = prefs.getStatFor(todayKey).resisted,
            streak = prefs.getStreak(todayKey, previousKeys),
            dailyLimit = dailyLimit,
            hasPermission = hasPermission
        )
    }

    /** 集計結果を表示に変換する。軽い処理のみ */
    private fun buildViews(context: Context, s: Snapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_usage)

        views.setTextViewText(R.id.widgetTime, formatDuration(s.totalMinutes))
        views.setTextViewText(R.id.widgetLaunches, "${s.totalLaunches}回")
        views.setTextViewText(R.id.widgetResisted, "${s.resisted}")
        views.setTextViewText(R.id.widgetStreak, "${s.streak}日")

        if (s.dailyLimit > 0) {
            val pct = ((s.totalMinutes.toFloat() / s.dailyLimit) * 100).toInt().coerceIn(0, 100)
            views.setProgressBar(R.id.widgetProgress, 100, pct, false)
            views.setTextViewText(R.id.widgetLimitLabel, "上限 ${formatDuration(s.dailyLimit)}")
            views.setViewVisibility(R.id.widgetProgress, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.widgetLimitLabel, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widgetProgress, android.view.View.GONE)
            views.setViewVisibility(R.id.widgetLimitLabel, android.view.View.GONE)
        }

        if (!s.hasPermission) {
            views.setTextViewText(R.id.widgetTime, "--")
            views.setTextViewText(R.id.widgetLimitLabel, "使用状況の許可が必要")
            views.setViewVisibility(R.id.widgetLimitLabel, android.view.View.VISIBLE)
        }

        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, pi)

        return views
    }

    private fun formatDuration(minutes: Int): String = when {
        minutes >= 60 -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) "${h}時間" else "${h}時間${m}分"
        }
        else -> "${minutes}分"
    }

    companion object {
        /**
         * 外部から更新を要求する。
         * 介入が起きた直後など、数字が変わったタイミングで呼ぶ。
         */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, UsageWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return

            val intent = Intent(context, UsageWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
