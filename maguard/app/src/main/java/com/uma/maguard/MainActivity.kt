package com.uma.maguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import androidx.palette.graphics.Palette
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
     *
     * 参考画像（one sec のホーム画面）に合わせて、2列のグリッドで
     * 色分けされたカードとして並べる。「追加」タイルは常に最後のセルとして
     * 一緒に並べるので、別立ての追加ボタンはもう置いていない。
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

        val rowGap = (10 * resources.displayMetrics.density).toInt()
        val cellCount = entries.size + 1 // +1 は「追加」タイルの分
        var index = 0
        while (index < cellCount) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = rowGap }
            }
            for (col in 0 until 2) {
                if (index >= cellCount) break
                val cell = if (index < entries.size) {
                    val (packageName, label, config) = entries[index]
                    buildAppCard(pm, packageName, label, config, row)
                } else {
                    buildAddTile(row)
                }
                if (col == 0) {
                    (cell.layoutParams as LinearLayout.LayoutParams).marginEnd = rowGap
                }
                row.addView(cell)
                index++
            }
            container.addView(row)
        }
    }

    private fun buildAppCard(
        pm: PackageManager,
        packageName: String,
        label: String,
        config: Prefs.AppConfig,
        parent: LinearLayout
    ): View {
        val card = LayoutInflater.from(this).inflate(R.layout.item_app_card, parent, false)
        val icon = loadAppIcon(pm, packageName)
        card.findViewById<ImageView>(R.id.appIcon).setImageDrawable(icon)
        card.findViewById<TextView>(R.id.appLabel).text = label
        card.findViewById<TextView>(R.id.appDetail).text = "${config.pauseSeconds}秒待つ"
        applyDynamicCardColor(card, icon)
        card.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        return card
    }

    private fun buildAddTile(parent: LinearLayout): View {
        val tile = LayoutInflater.from(this).inflate(R.layout.item_app_add_tile, parent, false)
        tile.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        return tile
    }

    /**
     * アプリアイコンから代表色を抽出し、カードの背景色として使う
     * （参考画像で Opera のカードが赤いのと同じ考え方）。
     * 抽出した色をそのまま使うと、アイコンによっては明るすぎて白文字が
     * 沈む・暗すぎて他のカードと見分けがつかない、ということが起きるため、
     * 明度・彩度を白文字向けの範囲に補正してから使う。
     */
    private fun applyDynamicCardColor(card: View, icon: Drawable?) {
        // loadAppIcon はアイコン取得に失敗すると null を返しうる。
        // その場合は抽出自体をスキップしてフォールバック色を使う。
        val extracted = icon?.let {
            try {
                val bitmap = drawableToBitmap(it)
                val palette = Palette.from(bitmap).generate()
                (palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch)?.rgb
            } catch (e: Exception) {
                null
            }
        } ?: ContextCompat.getColor(this, R.color.appCardFallback)

        (card.background?.mutate() as? GradientDrawable)?.setColor(readableCardColor(extracted))
    }

    private fun readableCardColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[2] > 0.72f) hsv[2] = 0.55f
        if (hsv[2] < 0.28f) hsv[2] = 0.38f
        if (hsv[1] < 0.28f) hsv[1] = 0.4f
        return Color.HSVToColor(hsv)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
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
