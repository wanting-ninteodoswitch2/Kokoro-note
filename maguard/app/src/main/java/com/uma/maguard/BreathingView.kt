package com.uma.maguard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * 呼吸に合わせて膨らみ・縮む円を描く。
 *
 * ＜なぜ数字のカウントダウンではなく呼吸なのか＞
 * one sec の大規模調査（Haliburton et al., CHI 2024）では、
 * 呼吸エクササイズが既定かつ最もよく使われる摩擦だった。
 * 一方で最も使われなかったのは「端末を回す」で、
 * 理由は人前でやると目立つからだと分析されている。
 *
 * つまり摩擦の効果は「強さ」だけでは決まらない。
 * 日常のどこでも抵抗なく実行できるかどうかが、
 * 継続して使われるかを左右する。
 *
 * 呼吸は、電車の中でも職場でも、誰にも気づかれずにできる。
 * 数字が減るのを眺めるより、身体の動作を伴う方が
 * 「いま自分は間を取っている」という自覚も生まれやすい。
 */
class BreathingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress = 0f          // 0=最小, 1=最大
    private var animator: ValueAnimator? = null

    // 一時停止画面は藍〜ラベンダーのグラデーション背景（pauseGradient系）を
    // 独自に持つため、他画面のアクセント色（青）ではなく白系のトークンを使う。
    // resources 経由にして、ライト/ダークどちらのグラデーションの上でも
    // 見やすい白のまま保つ。
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.pauseTextOnGradient)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.pauseTextOnGradient)
        style = Paint.Style.STROKE
        strokeWidth = 2f * context.resources.displayMetrics.density
        alpha = 90
    }

    /**
     * 呼吸のサイクルを開始する。
     * 4秒で吸って4秒で吐く、というゆっくりした周期にしている。
     * 速すぎると落ち着かず、遅すぎると待たされている感覚が強くなる。
     */
    fun start() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = minOf(width, height) / 2f - ringPaint.strokeWidth

        // 外周のリングは呼吸の最大幅を示す固定の目印
        canvas.drawCircle(cx, cy, maxRadius, ringPaint)

        // 内側の円が呼吸に合わせて伸縮する
        val minRadius = maxRadius * 0.35f
        val radius = minRadius + (maxRadius - minRadius) * progress

        // 大きいほど薄くする。膨らみきったところで軽さを感じさせるため
        circlePaint.alpha = (200 - 90 * progress).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius, circlePaint)
    }

    companion object {
        // 片道4秒（吸う4秒 → 吐く4秒 で1周期8秒）
        private const val CYCLE_MS = 4000L
    }
}
