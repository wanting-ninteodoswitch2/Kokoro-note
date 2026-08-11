package com.uma.maguard

import android.animation.ValueAnimator
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 「見る」を押すまでに挟む摩擦を制御する。
 *
 * ＜なぜ摩擦が要るのか＞
 * 待ち時間だけの介入は、慣れると無意識に待てるようになる。
 * カウントダウンが終わるのを待ってタップする、という一連の動作が
 * それ自体ルーティン化してしまう。
 *
 * ひと手間 —— 長押し、文章の書き写し —— を挟むと、
 * 「開く」という判断に意識を戻さざるを得なくなる。
 *
 * ＜設計＞
 * オーバーレイのビューを受け取り、モードに応じて表示を切り替える。
 * 条件を満たしたら onUnlocked を呼ぶ。
 */
class FrictionController(
    private val context: Context,
    private val root: View,
    private val mode: Prefs.FrictionMode,
    private val onUnlocked: () -> Unit
) {

    private val openButton: Button = root.findViewById(R.id.openButton)
    private val frictionGroup: View = root.findViewById(R.id.frictionGroup)
    private val frictionPrompt: TextView = root.findViewById(R.id.frictionPrompt)
    private val frictionInput: EditText = root.findViewById(R.id.frictionInput)
    private val frictionProgress: ProgressBar = root.findViewById(R.id.frictionProgress)

    private var holdAnimator: ValueAnimator? = null
    private var holdCancelled = false

    /** 摩擦の仕掛けを有効にする。カウントダウン終了後に呼ぶ */
    fun attach() {
        when (mode) {
            Prefs.FrictionMode.NONE -> setupNone()
            Prefs.FrictionMode.LONG_PRESS -> setupLongPress()
            Prefs.FrictionMode.TYPE_TEXT -> setupTypeText()
        }
    }

    fun detach() {
        holdCancelled = true
        holdAnimator?.cancel()
        holdAnimator = null
    }

    // ---------- なし ----------

    private fun setupNone() {
        frictionGroup.visibility = View.GONE
        openButton.setOnClickListener { onUnlocked() }
    }

    // ---------- 長押し ----------

    /**
     * 3秒間押し続けないと開けない。
     * 進捗バーで残りが見えるようにして、
     * 「指を離せばやめられる」ことを常に意識させる。
     */
    private fun setupLongPress() {
        frictionGroup.visibility = View.VISIBLE
        frictionInput.visibility = View.GONE
        frictionProgress.visibility = View.VISIBLE
        frictionProgress.progress = 0
        frictionPrompt.text = "押し続けてください"
        openButton.text = "長押しで開く"

        openButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startHold()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelHold()
                    view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * ValueAnimator.cancel() でも onAnimationEnd は呼ばれる。
     * 進捗の値だけで判定すると、指を離した瞬間の値によっては
     * 誤って解除されてしまうため、明示的なフラグで区別する。
     */
    private fun startHold() {
        holdAnimator?.cancel()
        holdCancelled = false
        holdAnimator = ValueAnimator.ofInt(0, 100).apply {
            duration = HOLD_DURATION_MS
            addUpdateListener { frictionProgress.progress = it.animatedValue as Int }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!holdCancelled) onUnlocked()
                }
            })
            start()
        }
    }

    private fun cancelHold() {
        holdCancelled = true
        holdAnimator?.cancel()
        holdAnimator = null
        frictionProgress.progress = 0
    }

    // ---------- 文章を書き写す ----------

    /**
     * 指定された文章を正確に書き写さないと開けない。
     * 一番強い抑止力。書き写している間に冷静になることを狙う。
     *
     * 長押しメニューを封じているが、これは「無意識のコピペ」を
     * 防ぐ程度のもので、完全な回避防止ではない。IMEの履歴や
     * 他の入力経路までは塞げないし、本気で回避しようと思えばできる。
     *
     * ただしこの機能の目的は不正防止ではなく、
     * 「惰性で開こうとしている自分に気づかせる」ことなので、
     * その水準の摩擦があれば十分と考えている。
     */
    private fun setupTypeText() {
        val target = Prefs(context).getTypeText()

        frictionGroup.visibility = View.VISIBLE
        frictionInput.visibility = View.VISIBLE
        frictionProgress.visibility = View.GONE
        frictionPrompt.text = "「$target」"
        frictionInput.hint = "上の文章を書き写してください"
        frictionInput.setText("")
        // 長押しメニュー（貼り付け）を封じる。完全ではないが、
        // 手が勝手に動くタイプの回避は防げる。
        // setTextIsSelectable(false) は EditText を編集不能にしてしまうため使わない。
        frictionInput.isLongClickable = false
        frictionInput.setOnLongClickListener { true }
        frictionInput.customInsertionActionModeCallback = NoSelectionActionMode()
        frictionInput.customSelectionActionModeCallback = NoSelectionActionMode()

        openButton.isEnabled = false
        openButton.alpha = 0.4f

        frictionInput.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString()?.trim() ?: ""
                // target が空だと「何も入力しなくても一致」になってしまう。
                // 現状 Prefs 側で空を弾いているが、そこが変わっても
                // 摩擦が消えないよう、ここでも確認しておく。
                val ok = target.isNotBlank() && typed == target.trim()
                openButton.isEnabled = ok
                openButton.alpha = if (ok) 1f else 0.4f
                // 途中経過を色で示す。入力欄は常に明色カード（pauseInputBackground）
                // なので、以前の「透明な暗い背景」向けだった配色（白文字がデフォルト）
                // のままだと読めなくなる。カードの上で完結する色に揃える。
                frictionInput.setTextColor(
                    when {
                        typed.isEmpty() -> ContextCompat.getColor(context, R.color.pauseInputText)
                        target.startsWith(typed) -> ContextCompat.getColor(context, R.color.pauseAccent)
                        else -> ContextCompat.getColor(context, R.color.pauseInputError)
                    }
                )
            }
        })

        openButton.setOnClickListener { onUnlocked() }
    }

    /** TextWatcherは3メソッド必須なので、使わない分をまとめる */
    private abstract class SimpleTextWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
    }

    /** テキスト選択メニュー（コピー/貼り付け）を出さないためのコールバック */
    private class NoSelectionActionMode : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?) = false
        override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
    }

    companion object {
        private const val HOLD_DURATION_MS = 3000L
    }
}
