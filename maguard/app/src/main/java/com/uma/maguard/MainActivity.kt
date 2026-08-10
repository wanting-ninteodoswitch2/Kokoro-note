package com.uma.maguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * メイン画面。役割は3つ。
 *  1. セットアップ状況（3つの権限）の案内と、設定画面へのショートカット
 *  2. 今日の統計と連続日数の表示
 *  3. 対象アプリ（すでに追加済みのもの）のプレビュー表示。
 *     追加・削除・詳細設定は AppPickerActivity で行う。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var targets: MutableMap<String, Prefs.AppConfig> = mutableMapOf()

    // Android 13 (API 33) 以降、通知の表示にはユーザーの明示的な許可が要る。
    // これがないと一時停止画面の通知自体は失敗しないが、通知は出ない。
    // マニフェストに POST_NOTIFICATIONS を宣言するだけでは付与されないため、
    // 通常の実行時パーミッションとしてここで要求する。
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒否されても致命的ではない */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        targets = prefs.getTargets().toMutableMap()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        findViewById<Button>(R.id.addAppButton).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        findViewById<ImageView>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.openSettingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.timeRulesButton).setOnClickListener {
            startActivity(Intent(this, TimeRulesActivity::class.java))
        }

        findViewById<Button>(R.id.reasonsButton).setOnClickListener {
            showReasonsDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        // 設定画面やAppPickerActivityから戻ってきたときに表示を更新する
        targets = prefs.getTargets().toMutableMap()
        refreshStatus()
        refreshStats()
        refreshTargetApps()
    }

    // ---------- セットアップ状況 ----------

    /**
     * 4つの権限のうち、動作の要となる3つ（アプリ検知・重ねて表示・
     * 使用状況へのアクセス）が揃っているかだけをここで確認する。
     * 一度揃えたユーザーには不要な情報なので、揃っている間は
     * バナーごと非表示にし、詳細は歯車アイコンから開く設定画面に任せる。
     *
     * 電池最適化はここでは見ない。除外できていなくてもアプリ自体は動くため
     * （しばらく経つと検知が止まりやすくなる、という程度の影響）、
     * 「動作に必須」という強い表現のバナーに含めるほどではないと判断した。
     */
    private fun refreshStatus() {
        val accessibilityOk = MaAccessibilityService.isEnabled(this)
        val overlayOk = Settings.canDrawOverlays(this)
        val usageOk = UsageTracker(this).hasPermission()
        val allOk = accessibilityOk && overlayOk && usageOk

        findViewById<TextView>(R.id.setupSummary).text = if (allOk) {
            "準備完了。対象アプリを選べば動きます。"
        } else {
            "動かすには、設定を開いて必要な項目をオンにしてください。"
        }
        findViewById<LinearLayout>(R.id.setupBanner).visibility =
            if (allOk) View.GONE else View.VISIBLE
    }

    /** 権限や対象アプリのON/OFF状態を、色付きのピルとして表示する共通処理 */
    private fun applyStatusPill(view: TextView, ok: Boolean) {
        view.text = if (ok) "有効" else "未設定"
        view.setBackgroundResource(if (ok) R.drawable.status_pill_ok else R.drawable.status_pill_pending)
        view.setTextColor(resources.getColor(if (ok) R.color.resisted else R.color.textMuted, theme))
    }

    // ---------- 統計 ----------

    private fun refreshStats() {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN)
        val cal = Calendar.getInstance()
        val todayKey = fmt.format(cal.time)

        val previousKeys = (1..60).map {
            cal.add(Calendar.DAY_OF_YEAR, -1)
            fmt.format(cal.time)
        }

        val today = prefs.getStatFor(todayKey)
        val streak = prefs.getStreak(todayKey, previousKeys)

        findViewById<TextView>(R.id.statResisted).text = today.resisted.toString()
        findViewById<TextView>(R.id.statOpened).text = today.opened.toString()
        findViewById<TextView>(R.id.statStreak).text = streak.toString()

        // 7日間グラフを更新
        findViewById<WeeklyChartView>(R.id.weeklyChart).setStats(prefs.getRecentStats(7))

        // いま効いている時間帯ルールがあれば表示する
        val activeRule = prefs.findActiveRule()
        val ruleStatus = findViewById<TextView>(R.id.activeRuleText)
        if (activeRule != null) {
            ruleStatus.visibility = View.VISIBLE
            ruleStatus.text = "いま適用中: ${activeRule.label}（${activeRule.rangeLabelWithNote()}）"
        } else {
            ruleStatus.visibility = View.GONE
        }
    }

    // ---------- 対象アプリのプレビュー ----------

    /**
     * すでに対象になっているアプリだけを、必要なぶんだけ表示する。
     *
     * 以前は端末の全アプリを ListView でここに埋め込んでいたため、
     * リスト自体が大量の行数を抱えることになり、上のセットアップ項目に
     * スクロール領域を圧迫されて一度に2〜3件しか見えなかった。
     * 追加・削除・詳細設定は AppPickerActivity 側に任せ、
     * ここは「いま何が対象か」を確認するだけの読み取り専用の表示にする。
     * 件数は少ない前提なので ListView は使わず、行を直接 addView する。
     */
    private fun refreshTargetApps() {
        val container = findViewById<LinearLayout>(R.id.targetAppsContainer)
        container.removeAllViews()

        val pm = packageManager
        val entries = targets.keys.mapNotNull { pkg ->
            val config = targets[pkg] ?: return@mapNotNull null
            try {
                val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                Triple(pkg, label, config)
            } catch (e: PackageManager.NameNotFoundException) {
                // アンインストール済みなど。一覧には出さない
                null
            }
        }.sortedBy { it.second }

        findViewById<TextView>(R.id.emptyTargetsText).visibility =
            if (entries.isEmpty()) View.VISIBLE else View.GONE

        entries.forEach { (packageName, label, config) ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_app, container, false)
            row.findViewById<ImageView>(R.id.appIcon).setImageDrawable(loadAppIcon(pm, packageName))
            row.findViewById<TextView>(R.id.appLabel).text = label
            row.findViewById<TextView>(R.id.appDetail).text = buildString {
                append("${config.pauseSeconds}秒待つ / ${config.graceMinutes}分は再表示しない")
                if (config.usageLimitMin > 0) append(" / ${config.usageLimitMin}分で再確認")
                if (config.dailyLimitMin > 0) append(" / 1日${config.dailyLimitMin}分まで")
                if (config.dailyLaunchLimit > 0) append(" / 1日${config.dailyLaunchLimit}回まで")
                if (config.friction != Prefs.FrictionMode.NONE) append(" / ${config.friction.label()}")
            }
            applyStatusPill(row.findViewById(R.id.appToggle), ok = true)
            row.findViewById<Button>(R.id.appSettingsButton).visibility = View.GONE
            row.setOnClickListener {
                startActivity(Intent(this, AppPickerActivity::class.java))
            }
            container.addView(row)
        }
    }

    /** アプリ本来のアイコンを読み込む。取得できない場合は汎用アイコンで代替する */
    private fun loadAppIcon(pm: PackageManager, packageName: String) =
        try {
            pm.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            ContextCompat.getDrawable(this, R.drawable.ic_apps)
        }

    /**
     * 「理由を書く」モードで入力された内容を振り返る。
     * どんなときに開いているかのパターンが見えるのが狙い。
     */
    private fun showReasonsDialog() {
        val reasons = prefs.getReasons(50)
        if (reasons.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("開いた理由")
                .setMessage(
                    "まだ記録がありません。\n\n" +
                        "アプリの設定で摩擦を「理由を書く」にすると、" +
                        "開くたびに入力した理由がここに残ります。"
                )
                .setPositiveButton("閉じる", null)
                .show()
            return
        }

        val fmt = SimpleDateFormat("M/d HH:mm", Locale.JAPAN)
        val pm = packageManager
        val items = reasons.map { entry ->
            val appLabel = try {
                pm.getApplicationLabel(pm.getApplicationInfo(entry.packageName, 0)).toString()
            } catch (e: Exception) {
                entry.packageName
            }
            "${fmt.format(Date(entry.timestamp))}  $appLabel\n${entry.reason}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("開いた理由（直近${reasons.size}件）")
            .setItems(items, null)
            .setPositiveButton("閉じる", null)
            .show()
    }
}
