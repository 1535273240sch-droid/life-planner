package com.lifeplanner.ai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lifeplanner.ai.ui.theme.LpColors

/** 一条折线。values 中的 null 表示当天无数据：断线，不补 0。 */
data class ChartSeries(
    val label: String,
    val values: List<Float?>,
    val color: Color,
)

/**
 * 极简折线图（不引第三方图表库，保证 APK 完全离线自包含）。
 *
 * 本次重绘的三件事（DESIGN.md §1 "层次靠透明度，不靠描边"）：
 * 1. 折线下方加渐变填充：面积色是最低饱和的同色，只用来"托住"曲线，不抢数据；
 * 2. 曲线做平滑处理：日粒度数据用折线会出现大量尖锐锯齿，读起来像心跳；
 * 3. 网格虚化 + 数据点带光晕：网格退到背景，数据点成为视觉焦点。
 *
 * 仍然遵守原约定：null 断线不补 0；横轴标签按宽度抽稀。
 */
@Composable
fun LpLineChart(
    labels: List<String>,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    valueSuffix: String = "",
) {
    val allValues = series.flatMap { it.values }.filterNotNull()
    if (labels.isEmpty() || allValues.isEmpty()) {
        LpEmptyState(title = "暂无数据", subtitle = "记录几天后这里会出现趋势")
        return
    }

    val minValue = minOf(0f, allValues.min())
    val maxValue = allValues.max().let { if (it <= minValue) minValue + 1f else it }
    val range = maxValue - minValue

    Column(modifier = modifier.fillMaxWidth()) {
        // 图例
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (s in series) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(s.color, CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = s.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = LpColors.TextSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.Top) {
            // Y 轴刻度：与 3 条网格对齐
            Column(
                modifier = Modifier.height(height),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = fmt(maxValue) + valueSuffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = LpColors.TextMuted,
                )
                Text(
                    text = fmt((minValue + maxValue) / 2) + valueSuffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = LpColors.TextMuted,
                )
                Text(
                    text = fmt(minValue) + valueSuffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = LpColors.TextMuted,
                )
            }
            Spacer(Modifier.width(10.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height),
            ) {
                val w = size.width
                val h = size.height

                fun xAt(index: Int): Float =
                    if (labels.size <= 1) w / 2f else w * index / (labels.size - 1).toFloat()

                fun yAt(value: Float): Float = h - (value - minValue) / range * h

                // 网格：虚线 + 低透明度，只保留"有刻度"的暗示
                val gridEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 10f), 0f)
                for (i in 0..2) {
                    val y = h * i / 2f
                    drawLine(
                        color = LpColors.Border.copy(alpha = 0.65f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f,
                        pathEffect = gridEffect,
                    )
                }

                // 先把每条序列按 null 切成连续段：断线处不连线，也不做面积填充
                val segmented: List<Pair<ChartSeries, List<List<Offset>>>> = series.map { s ->
                    val groups = ArrayList<List<Offset>>()
                    var current = ArrayList<Offset>()
                    s.values.forEachIndexed { i, v ->
                        if (v == null) {
                            if (current.isNotEmpty()) {
                                groups.add(current.toList())
                                current = ArrayList()
                            }
                        } else {
                            current.add(Offset(xAt(i), yAt(v)))
                        }
                    }
                    if (current.isNotEmpty()) groups.add(current.toList())
                    s to groups
                }

                // 1) 面积填充：放在所有曲线下面，避免遮住别的序列
                for ((s, groups) in segmented) {
                    for (points in groups) {
                        if (points.size < 2) continue
                        drawPath(
                            path = areaPath(points, bottom = h),
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    s.color.copy(alpha = 0.30f),
                                    s.color.copy(alpha = 0.02f),
                                ),
                                startY = 0f,
                                endY = h,
                            ),
                        )
                    }
                }

                // 2) 曲线
                for ((s, groups) in segmented) {
                    for (points in groups) {
                        if (points.size < 2) continue
                        drawPath(
                            path = smoothPath(points),
                            color = s.color,
                            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                        )
                    }
                }

                // 3) 数据点：点少时带光晕；30 天这种密集数据只画小点，否则会糊成一片
                val dense = labels.size > 10
                for ((s, groups) in segmented) {
                    for (points in groups) {
                        for (p in points) {
                            if (!dense) {
                                drawCircle(
                                    color = s.color.copy(alpha = 0.18f),
                                    radius = 7f,
                                    center = p,
                                )
                            }
                            drawCircle(
                                color = s.color,
                                radius = if (dense) 2f else 3.2f,
                                center = p,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // 横轴：最多显示 6 个标签，避免拥挤
        val step = ((labels.size + 5) / 6).coerceAtLeast(1)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val shown = labels.indices.filter { it % step == 0 || it == labels.lastIndex }
            for (i in shown) {
                Text(
                    text = labels[i],
                    style = MaterialTheme.typography.labelSmall,
                    color = LpColors.TextMuted,
                )
            }
        }
    }
}

/**
 * 平滑折线：用「中点控制」的三次贝塞尔，每段只向左右各推半个横坐标。
 *
 * 为什么不用 Catmull-Rom：日数据经常出现陡升陡降，Catmull-Rom 会过冲，
 * 曲线跑到 0 以下（学习时长不可能为负）看起来像画错了。
 */
private fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    for (i in 0 until points.lastIndex) {
        val p = points[i]
        val q = points[i + 1]
        val midX = (p.x + q.x) / 2f
        path.cubicTo(midX, p.y, midX, q.y, q.x, q.y)
    }
    return path
}

/** 面积路径：同一条平滑线向下闭合成面，用于渐变填充。 */
private fun areaPath(points: List<Offset>, bottom: Float): Path {
    val path = smoothPath(points)
    path.lineTo(points.last().x, bottom)
    path.lineTo(points.first().x, bottom)
    path.close()
    return path
}

/** 数字格式：整数不带小数点，其余保留一位，避免坐标轴上出现 12.0 这种噪音。 */
private fun fmt(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.1f", v)