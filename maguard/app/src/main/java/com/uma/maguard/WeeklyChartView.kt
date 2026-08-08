package com.uma.maguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 7日間の記録を積み上げ棒グラフで描くカスタムView。
 *
 * ＜カスタムViewの基本＞
 * Androidの標準部品に欲しいものがないときは、Viewを継承して
 * onDraw() の中に「Canvasへの描画命令」を書く。外部ライブラリを
 * 入れなくても、棒グラフくらいならこれで十分作れる。
 *
 * onDraw は画面の再描画のたびに呼ばれるので、
 * ここでオブジェクトを new すると重くなる。Paintなどは
 * あらかじめフィールドとして作っておくのが定石。
 */
class WeeklyChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var stats: List<Prefs.DayStat> = emptyList()

    private val resistedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8A659")   // accent
    }
    private val openedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D4258")   // track
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B90A3")   // textMuted
        // 画素数で指定すると端末の解像度によって大きさが変わってしまうため、
        // 画面密度を掛けて dp 相当に揃える
        textSize = 10f * context.resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2C3142")   // border
    }

    fun setStats(newStats: List<Prefs.DayStat>) {
        stats = newStats
        invalidate()   // 再描画を要求する。これを呼ばないと画面が更新されない
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (stats.isEmpty()) return

        val density = resources.displayMetrics.density
        val labelHeight = 14f * density
        val chartHeight = height - labelHeight
        val colWidth = width.toFloat() / stats.size
        val barWidth = colWidth * 0.4f
        val corner = barWidth / 3f

        // 一番多い日を基準に高さを正規化する（最低1にして0除算を防ぐ）
        val maxTotal = stats.maxOf { it.resisted + it.opened }.coerceAtLeast(1)

        stats.forEachIndexed { index, stat ->
            val centerX = colWidth * index + colWidth / 2f
            val left = centerX - barWidth / 2f
            val right = centerX + barWidth / 2f
            val total = stat.resisted + stat.opened

            if (total == 0) {
                // 記録がない日は、薄い線だけ引いておく
                canvas.drawRoundRect(
                    RectF(left, chartHeight - 6f, right, chartHeight),
                    3f, 3f, emptyPaint
                )
            } else {
                val totalHeight = (total.toFloat() / maxTotal) * (chartHeight - 12f)
                val openedHeight = (stat.opened.toFloat() / total) * totalHeight
                val resistedHeight = totalHeight - openedHeight

                // 下：見た回数（グレー）
                if (openedHeight > 0) {
                    canvas.drawRect(
                        left, chartHeight - openedHeight, right, chartHeight, openedPaint
                    )
                }
                // 上：やめた回数（アクセント色）
                if (resistedHeight > 0) {
                    // 下端に corner を足しているのは、下側の角丸を
                    // グレーの矩形に隠して「上だけ角丸」に見せるため。
                    //
                    // ただし見た回数が0の日はグレーの矩形が存在しないので、
                    // そのまま足すとグラフの底辺を突き抜けて
                    // 日付ラベルに重なってしまう。
                    // 全部やめられた日ほど表示が崩れる、という
                    // おかしなことになるので、その場合は足さない。
                    val bottom = if (openedHeight > 0f) {
                        chartHeight - openedHeight + corner
                    } else {
                        chartHeight
                    }
                    canvas.drawRoundRect(
                        RectF(
                            left,
                            chartHeight - totalHeight,
                            right,
                            bottom
                        ),
                        corner, corner, resistedPaint
                    )
                }
            }

            // 日付ラベル（末尾2桁＝日）
            val dayLabel = stat.date.takeLast(2)
            canvas.drawText(dayLabel, centerX, height - 2f * density, labelPaint)
        }
    }
}
