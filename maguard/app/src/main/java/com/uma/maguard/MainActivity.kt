package com.uma.maguard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
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
 *  3. 対象アプリの選択と、アプリごとの詳細設定
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var apps: List<AppInfo>
    private lateinit var adapter: AppListAdapter
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
        apps = loadLaunchableApps()

        adapter = AppListAdapter()
        findViewById<ListView>(R.id.appListView).adapter = adapter

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.overlayButton).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            showBatteryGuide()
        }

        findViewById<Button>(R.id.timeRulesButton).setOnClickListener {
            startActivity(Intent(this, TimeRulesActivity::class.java))
        }

        findViewById<Button>(R.id.reasonsButton).setOnClickListener {
            showReasonsDialog()
        }

        findViewById<Button>(R.id.usageStatsButton).setOnClickListener {
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
        // 設定画面から戻ってきたときに、権限の状態表示を更新する
        refreshStatus()
        refreshStats()
    }

    // ---------- セットアップ状況 ----------

    private fun refreshStatus() {
        val accessibilityOk = MaAccessibilityService.isEnabled(this)
        val overlayOk = Settings.canDrawOverlays(this)
        val batteryOk = isIgnoringBatteryOptimizations()
        val usageOk = UsageTracker(this).hasPermission()

        findViewById<TextView>(R.id.accessibilityStatus).text = statusLabel(accessibilityOk)
        findViewById<TextView>(R.id.overlayStatus).text = statusLabel(overlayOk)
        findViewById<TextView>(R.id.batteryStatus).text = statusLabel(batteryOk)
        findViewById<TextView>(R.id.usageStatsStatus).text = statusLabel(usageOk)

        val allOk = accessibilityOk && overlayOk && usageOk
        findViewById<TextView>(R.id.setupSummary).text = if (allOk) {
            "準備完了。対象アプリを選べば動きます。"
        } else {
            "動かすには、下の項目をオンにしてください。"
        }
    }

    private fun statusLabel(ok: Boolean) = if (ok) "有効" else "未設定"

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

    // ---------- アプリ一覧 ----------

    private fun loadLaunchableApps(): List<AppInfo> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }

    /**
     * アプリ一覧のアダプター。
     * 行をタップ＝対象のオン/オフ切り替え、「設定」ボタン＝詳細設定ダイアログ。
     */
    private inner class AppListAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_app, parent, false)

            val app = apps[position]
            val config = targets[app.packageName]

            view.findViewById<TextView>(R.id.appLabel).text = app.label
            view.findViewById<TextView>(R.id.appDetail).text = if (config != null) {
                buildString {
                    append("${config.pauseSeconds}秒待つ / ${config.graceMinutes}分は再表示しない")
                    if (config.usageLimitMin > 0) append(" / ${config.usageLimitMin}分で再確認")
                    if (config.dailyLimitMin > 0) append(" / 1日${config.dailyLimitMin}分まで")
                    if (config.dailyLaunchLimit > 0) append(" / 1日${config.dailyLaunchLimit}回まで")
                    if (config.friction != Prefs.FrictionMode.NONE) {
                        append(" / ${config.friction.label()}")
                    }
                }
            } else {
                "対象外"
            }
            view.findViewById<TextView>(R.id.appToggle).text = if (config != null) "ON" else "OFF"

            view.setOnClickListener {
                if (targets.containsKey(app.packageName)) {
                    targets.remove(app.packageName)
                } else {
                    targets[app.packageName] = Prefs.AppConfig()
                }
                prefs.saveTargets(targets)
                notifyDataSetChanged()
                UsageWidgetProvider.requestUpdate(this@MainActivity)
            }

            val settingsButton = view.findViewById<Button>(R.id.appSettingsButton)
            settingsButton.visibility = if (config != null) View.VISIBLE else View.GONE
            settingsButton.setOnClickListener { showConfigDialog(app, config ?: Prefs.AppConfig()) }

            return view
        }
    }

    /**
     * アプリごとの停止秒数・猶予分数を設定するダイアログ。
     * SNSは長めに待たせる、業務用アプリは短くする、といった調整ができる。
     */
    private fun showConfigDialog(app: AppInfo, current: Prefs.AppConfig) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_app_config, null)

        val pauseSeek = view.findViewById<SeekBar>(R.id.pauseSeek)
        val pauseValue = view.findViewById<TextView>(R.id.pauseValue)
        val graceSeek = view.findViewById<SeekBar>(R.id.graceSeek)
        val graceValue = view.findViewById<TextView>(R.id.graceValue)
        val usageSeek = view.findViewById<SeekBar>(R.id.usageSeek)
        val usageValue = view.findViewById<TextView>(R.id.usageValue)
        val dailySeek = view.findViewById<SeekBar>(R.id.dailySeek)
        val dailyValue = view.findViewById<TextView>(R.id.dailyValue)
        val launchSeek = view.findViewById<SeekBar>(R.id.launchSeek)
        val launchValue = view.findViewById<TextView>(R.id.launchValue)
        val frictionSpinner = view.findViewById<android.widget.Spinner>(R.id.frictionSpinner)
        val frictionDesc = view.findViewById<TextView>(R.id.frictionDesc)
        val typeTextRow = view.findViewById<View>(R.id.typeTextRow)
        val typeTextInput = view.findViewById<android.widget.EditText>(R.id.typeTextInput)

        // SeekBarは0始まりなので、実際の値との差分を足し引きして扱う
        // one sec の研究で使われている範囲（3〜60秒、既定6秒）に合わせる。
        // 短すぎると摩擦にならず、長すぎるとアプリごと使われなくなる。
        pauseSeek.max = 57          // 3〜60秒
        pauseSeek.progress = current.pauseSeconds - 3
        pauseValue.text = "${current.pauseSeconds}秒"

        graceSeek.max = 59          // 1〜60分
        graceSeek.progress = current.graceMinutes - 1
        graceValue.text = "${current.graceMinutes}分"

        pauseSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                pauseValue.text = "${progress + 3}秒"
            }
        })
        graceSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                graceValue.text = "${progress + 1}分"
            }
        })

        // 使用時間の上限。0のときは「なし」と表示して機能オフを分かりやすくする
        usageSeek.max = 60                 // 0〜60分
        usageSeek.progress = current.usageLimitMin
        usageValue.text = usageLabel(current.usageLimitMin)
        usageSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                usageValue.text = usageLabel(progress)
            }
        })

        // 1日の合計使用時間の上限（0〜240分、5分刻み）
        dailySeek.max = 48
        dailySeek.progress = current.dailyLimitMin / 5
        dailyValue.text = dailyLabel(current.dailyLimitMin)
        dailySeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                dailyValue.text = dailyLabel(progress * 5)
            }
        })

        // 1日の起動回数の上限（0〜50回）
        launchSeek.max = 50
        launchSeek.progress = current.dailyLaunchLimit
        launchValue.text = launchLabel(current.dailyLaunchLimit)
        launchSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                launchValue.text = launchLabel(progress)
            }
        })

        // 摩擦の種類を選ぶ。選ぶたびに説明が変わる
        val modes = Prefs.FrictionMode.values()
        frictionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modes.map { it.label() }
        )
        frictionSpinner.setSelection(modes.indexOf(current.friction))
        typeTextInput.setText(prefs.getTypeText())

        fun applyFrictionUi(mode: Prefs.FrictionMode) {
            frictionDesc.text = mode.description()
            typeTextRow.visibility =
                if (mode == Prefs.FrictionMode.TYPE_TEXT) View.VISIBLE else View.GONE
        }
        applyFrictionUi(current.friction)

        frictionSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long
                ) {
                    applyFrictionUi(modes[position])
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                targets[app.packageName] = Prefs.AppConfig(
                    pauseSeconds = pauseSeek.progress + 3,
                    graceMinutes = graceSeek.progress + 1,
                    usageLimitMin = usageSeek.progress,
                    dailyLimitMin = dailySeek.progress * 5,
                    dailyLaunchLimit = launchSeek.progress,
                    friction = modes[frictionSpinner.selectedItemPosition]
                )
                if (modes[frictionSpinner.selectedItemPosition] == Prefs.FrictionMode.TYPE_TEXT) {
                    prefs.setTypeText(typeTextInput.text.toString())
                }
                prefs.saveTargets(targets)
                adapter.notifyDataSetChanged()
                UsageWidgetProvider.requestUpdate(this)
            }
            .setNegativeButton("キャンセル", null)
            .show()
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

    private fun usageLabel(minutes: Int) =
        if (minutes <= 0) "なし" else "${minutes}分"

    private fun dailyLabel(minutes: Int) = when {
        minutes <= 0 -> "上限なし"
        minutes >= 60 -> "${minutes / 60}時間${if (minutes % 60 > 0) "${minutes % 60}分" else ""}"
        else -> "${minutes}分"
    }

    private fun launchLabel(count: Int) =
        if (count <= 0) "上限なし" else "${count}回"

    /** SeekBarのリスナーは3つのメソッド実装が必須なので、使わない分をまとめた基底クラス */
    private open class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
