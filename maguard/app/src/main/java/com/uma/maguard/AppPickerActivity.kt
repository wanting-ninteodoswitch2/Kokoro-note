package com.uma.maguard

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 対象アプリの追加・削除・詳細設定をまとめて行う画面。
 *
 * 以前はこの一覧をメイン画面に直接埋め込んでいたため、上部の統計・
 * セットアップ項目にスクロール領域を圧迫され、一度に2〜3件しか
 * 見えない状態になっていた。ここを独立した画面に切り出し、
 * 検索で絞り込めるようにする。
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var allApps: List<AppInfo>
    private var filteredApps: List<AppInfo> = emptyList()
    private lateinit var adapter: AppListAdapter
    private var targets: MutableMap<String, Prefs.AppConfig> = mutableMapOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        prefs = Prefs(this)
        targets = prefs.getTargets().toMutableMap()
        allApps = loadLaunchableApps()
        filteredApps = allApps

        adapter = AppListAdapter()
        findViewById<ListView>(R.id.pickerListView).adapter = adapter

        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

        findViewById<EditText>(R.id.searchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })
    }

    private fun applyFilter(query: String) {
        filteredApps = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { it.label.contains(query, ignoreCase = true) }
        }
        adapter.notifyDataSetChanged()
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

    /** アプリ本来のアイコンを読み込む。取得できない場合は汎用アイコンで代替する */
    private fun loadAppIcon(targetPackageName: String) =
        try {
            packageManager.getApplicationIcon(targetPackageName)
        } catch (e: PackageManager.NameNotFoundException) {
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_apps)
        }

    /** 権限や対象アプリのON/OFF状態を、色付きのピルとして表示する共通処理 */
    private fun applyStatusPill(view: TextView, ok: Boolean) {
        view.text = if (ok) "ON" else "OFF"
        view.setBackgroundResource(if (ok) R.drawable.status_pill_ok else R.drawable.status_pill_pending)
        view.setTextColor(resources.getColor(if (ok) R.color.resisted else R.color.textMuted, theme))
    }

    /**
     * アプリ一覧のアダプター。
     * 行をタップ＝対象のオン/オフ切り替え、「設定」ボタン＝詳細設定ダイアログ。
     */
    private inner class AppListAdapter : BaseAdapter() {
        override fun getCount() = filteredApps.size
        override fun getItem(position: Int) = filteredApps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@AppPickerActivity)
                .inflate(R.layout.item_app, parent, false)

            val app = filteredApps[position]
            val config = targets[app.packageName]

            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(loadAppIcon(app.packageName))
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
            applyStatusPill(view.findViewById(R.id.appToggle), ok = config != null)

            view.setOnClickListener {
                if (targets.containsKey(app.packageName)) {
                    targets.remove(app.packageName)
                } else {
                    targets[app.packageName] = Prefs.AppConfig()
                }
                prefs.saveTargets(targets)
                notifyDataSetChanged()
                UsageWidgetProvider.requestUpdate(this@AppPickerActivity)
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
