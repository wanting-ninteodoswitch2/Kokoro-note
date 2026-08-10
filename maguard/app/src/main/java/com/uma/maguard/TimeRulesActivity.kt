package com.uma.maguard

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * 時間帯別ルールの管理画面。
 *
 * 「夜22時以降は30秒待たせる」「朝6〜8時は完全にブロック」といった
 * 時間帯ごとの振る舞いを設定する。記事にあった
 * 「朝一番にスマホを見るとドーパミンの急上昇を招く」という指摘に
 * 対応するための機能。
 */
class TimeRulesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var rules: MutableList<Prefs.TimeRule> = mutableListOf()
    private lateinit var adapter: RuleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_time_rules)

        prefs = Prefs(this)
        rules = prefs.getTimeRules().toMutableList()

        adapter = RuleAdapter()
        findViewById<ListView>(R.id.ruleListView).adapter = adapter

        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

        findViewById<Button>(R.id.addRuleButton).setOnClickListener {
            showRuleDialog(null)
        }

        findViewById<Button>(R.id.presetButton).setOnClickListener {
            showPresetDialog()
        }

        refreshEmptyState()
    }

    private fun refreshEmptyState() {
        findViewById<TextView>(R.id.emptyText).visibility =
            if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * よく使う設定をワンタップで追加できるようにする。
     * 自分で0から作るより、まず試してから調整する方が続きやすい。
     */
    private fun showPresetDialog() {
        val presets = listOf(
            Triple("朝いちばんは開かない", 6 * 60 to 8 * 60, true),
            Triple("夜は長めに待つ", 22 * 60 to 2 * 60, false),
            Triple("仕事中は長めに待つ", 9 * 60 to 18 * 60, false)
        )
        val labels = presets.map { (label, range, blocked) ->
            val (s, e) = range
            val fmt = { m: Int -> String.format(Locale.JAPAN, "%02d:%02d", m / 60, m % 60) }
            "$label（${fmt(s)}〜${fmt(e)}${if (blocked) " / ブロック" else ""}）"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("プリセットから追加")
            .setItems(labels) { _, which ->
                val (label, range, blocked) = presets[which]
                rules.add(
                    Prefs.TimeRule(
                        id = System.currentTimeMillis(),
                        label = label,
                        startMinute = range.first,
                        endMinute = range.second,
                        pauseSeconds = if (blocked) 5 else 20,
                        graceMinutes = 3,
                        blocked = blocked
                    )
                )
                save()
            }
            .show()
    }

    /**
     * ルールの新規作成・編集ダイアログ。
     * rule が null なら新規作成、そうでなければ編集。
     */
    private fun showRuleDialog(rule: Prefs.TimeRule?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_time_rule, null)

        val labelInput = view.findViewById<android.widget.EditText>(R.id.labelInput)
        val startButton = view.findViewById<Button>(R.id.startTimeButton)
        val endButton = view.findViewById<Button>(R.id.endTimeButton)
        val blockedCheck = view.findViewById<CheckBox>(R.id.blockedCheck)
        val pauseSeek = view.findViewById<SeekBar>(R.id.pauseSeek)
        val pauseValue = view.findViewById<TextView>(R.id.pauseValue)

        var startMinute = rule?.startMinute ?: (22 * 60)
        var endMinute = rule?.endMinute ?: (2 * 60)

        fun fmt(m: Int) = String.format(Locale.JAPAN, "%02d:%02d", m / 60, m % 60)

        labelInput.setText(rule?.label ?: "")
        startButton.text = fmt(startMinute)
        endButton.text = fmt(endMinute)
        blockedCheck.isChecked = rule?.blocked ?: false

        val initialPause = rule?.pauseSeconds ?: 20
        pauseSeek.max = 57                       // 3〜60秒
        pauseSeek.progress = initialPause - 3
        pauseValue.text = "${initialPause}秒"
        pauseSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                pauseValue.text = "${p + 3}秒"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        startButton.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                startMinute = h * 60 + m
                startButton.text = fmt(startMinute)
            }, startMinute / 60, startMinute % 60, true).show()
        }

        endButton.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                endMinute = h * 60 + m
                endButton.text = fmt(endMinute)
            }, endMinute / 60, endMinute % 60, true).show()
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (rule == null) "ルールを追加" else "ルールを編集")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val newRule = Prefs.TimeRule(
                    id = rule?.id ?: System.currentTimeMillis(),
                    label = labelInput.text.toString().ifBlank { "名前のないルール" },
                    startMinute = startMinute,
                    endMinute = endMinute,
                    pauseSeconds = pauseSeek.progress + 3,
                    graceMinutes = rule?.graceMinutes ?: 3,
                    blocked = blockedCheck.isChecked,
                    enabled = rule?.enabled ?: true
                )
                if (rule == null) {
                    rules.add(newRule)
                } else {
                    val index = rules.indexOfFirst { it.id == rule.id }
                    if (index >= 0) rules[index] = newRule
                }
                save()
            }
            .setNegativeButton("キャンセル", null)

        if (rule != null) {
            builder.setNeutralButton("削除") { _, _ ->
                rules.removeAll { it.id == rule.id }
                save()
            }
        }

        builder.show()
    }

    private fun save() {
        prefs.saveTimeRules(rules)
        adapter.notifyDataSetChanged()
        refreshEmptyState()
    }

    private inner class RuleAdapter : BaseAdapter() {
        override fun getCount() = rules.size
        override fun getItem(position: Int) = rules[position]
        override fun getItemId(position: Int) = rules[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@TimeRulesActivity)
                .inflate(R.layout.item_rule, parent, false)

            val rule = rules[position]
            view.findViewById<TextView>(R.id.ruleLabel).text = rule.label
            view.findViewById<TextView>(R.id.ruleDetail).text = buildString {
                append(rule.rangeLabelWithNote())
                append(" / ")
                append(if (rule.blocked) "開かない" else "${rule.pauseSeconds}秒待つ")
            }

            val toggle = view.findViewById<CheckBox>(R.id.ruleEnabled)
            toggle.setOnCheckedChangeListener(null)   // 再利用時の誤発火を防ぐ
            toggle.isChecked = rule.enabled
            toggle.setOnCheckedChangeListener { _, checked ->
                val index = rules.indexOfFirst { it.id == rule.id }
                if (index >= 0) {
                    rules[index] = rules[index].copy(enabled = checked)
                    prefs.saveTimeRules(rules)
                }
            }

            view.setOnClickListener { showRuleDialog(rule) }
            return view
        }
    }
}
