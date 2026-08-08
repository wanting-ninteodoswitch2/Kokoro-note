package com.uma.maguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 端末の再起動を検知する。
 *
 * AlarmManager に登録した予約は再起動で全て消える。
 * ただしこのアプリの場合、予約は「対象アプリを開いた時点」で
 * 張り直されるため、起動直後に復元すべき具体的な予約はない。
 *
 * ここでの役目は、ウィジェットの表示を最新にすることと、
 * アクセシビリティサービスが無効になっていないかを次回起動時に
 * 気づけるようにすること。
 *
 * なお、アクセシビリティサービス自体は端末再起動後も
 * OSが自動的に再開するため、ユーザーの再設定は不要。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                UsageWidgetProvider.requestUpdate(context)
            }
        }
    }
}
