package com.uma.maguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 4つの権限（アプリ検知・重ねて表示・使用状況・電池最適化）を確認・変更する画面。
 *
 * 以前はメイン画面に常時表示していたが、一度そろえてしまえば
 * 普段は見る必要のない情報をずっと表示し続けるのはノイズになる。
 * ここに切り出し、メイン画面には未設定のものがあるときだけ
 * バナーで案内する。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

        findViewById<android.widget.Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<android.widget.Button>(R.id.overlayButton).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<android.widget.Button>(R.id.batteryButton).setOnClickListener {
            showBatteryGuide()
        }

        findViewById<android.widget.Button>(R.id.usageStatsButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("使用状況へのアクセス")
                .setMessage(
                    "実際の使用時間を正確に測るために必要です。\n\n" +
                        "これがないと「今日どれだけ使ったか」が分からず、" +
                        "連続使用の再確認や1日の上限機能が働きません。\n\n" +
                        "次の画面で「ま。Guard」を探して、オンにしてください。"
                )
                .setPositiveButton("設定を開く") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton("閉じる", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val accessibilityOk = MaAccessibilityService.isEnabled(this)
        val overlayOk = Settings.canDrawOverlays(this)
        val batteryOk = isIgnoringBatteryOptimizations()
        val usageOk = UsageTracker(this).hasPermission()

        applyStatusPill(findViewById(R.id.accessibilityStatus), accessibilityOk)
        applyStatusPill(findViewById(R.id.overlayStatus), overlayOk)
        applyStatusPill(findViewById(R.id.batteryStatus), batteryOk)
        applyStatusPill(findViewById(R.id.usageStatsStatus), usageOk)

        val allOk = accessibilityOk && overlayOk && usageOk
        findViewById<TextView>(R.id.setupSummary).text = if (allOk) {
            "準備完了。対象アプリを選べば動きます。"
        } else {
            "動かすには、下の項目をオンにしてください。"
        }
    }

    /** 権限のON/OFF状態を、色付きのピルとして表示する共通処理 */
    private fun applyStatusPill(view: TextView, ok: Boolean) {
        view.text = if (ok) "有効" else "未設定"
        view.setBackgroundResource(if (ok) R.drawable.status_pill_ok else R.drawable.status_pill_pending)
        view.setTextColor(resources.getColor(if (ok) R.color.resisted else R.color.textMuted, theme))
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * バッテリー最適化の除外設定。
     * これをしないと、しばらく使わないうちにOSがサービスを止めてしまい、
     * 「なぜか検知されない」という一番ハマりやすい不具合が起きる。
     * メーカー独自OSの場合は追加の設定が必要なため、案内も出す。
     */
    private fun showBatteryGuide() {
        AlertDialog.Builder(this)
            .setTitle("バッテリー最適化の除外")
            .setMessage(
                "この設定をしないと、しばらく経つとOSが検知サービスを止めてしまい、" +
                    "一時停止画面が出なくなることがあります。\n\n" +
                    "次の画面で「ま。Guard」を探し、「制限なし」または" +
                    "「最適化しない」を選んでください。\n\n" +
                    "※Xiaomi / OPPO / Samsung などの機種では、これに加えて" +
                    "「自動起動」の許可が必要な場合があります。"
            )
            .setPositiveButton("設定を開く") { _, _ ->
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
                startActivity(intent)
            }
            .setNegativeButton("閉じる", null)
            .show()
    }
}
