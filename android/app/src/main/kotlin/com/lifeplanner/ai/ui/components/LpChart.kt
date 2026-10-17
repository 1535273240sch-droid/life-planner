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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
 * - 横轴标签按可用宽度抽稀显示；
 * - 该点为空时断开折线（与 core 的 StatsTrends 约定一致）。
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
                    Text(s.label, style = MaterialTheme.typography.labelSmall, color = LpColors.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.Top) {
            // Y 轴刻度
            Column(
                modifier = Modifier.height(height),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(fmt(maxValue) + valueSuffix, style = MaterialTheme.typography.labelSmall, color = LpColors.TextMuted)
                Text(fmt((minValue + maxValue) / 2) + valueSuffix, style = MaterialTheme.typography.labelSmall, color = LpColors.TextMuted)
                Text(fmt(minValue) + valueSuffix, style = MaterialTheme.typography.labelSmall, color = LpColors.TextMuted)
            }
            Spacer(Modifier.width(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height),
            ) {
                val w = size.width
                val h = size.height
                // 网格线
                for (i in 0..4) {
                    val y = h * i / 4f
                    drawLine(
                        color = LpColors.Border,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f,
                    )
                }

                fun xAt(index: Int): Float =
                    if (labels.size <= 1) w / 2f else w * index / (labels.size - 1).toFloat()

                fun yAt(value: Float): Float = h - (value - minValue) / range * h

                for (s in series) {
                    val path = Path()
                    var started = false
                    s.values.forEachIndexed { i, v ->
                        if (v == null) {
                            started = false
                        } else {
                            val point = Offset(xAt(i), yAt(v))
                            if (!started) {
                                path.moveTo(point.x, point.y)
                                started = true
                            } else {
                                path.lineTo(point.x, point.y)
                            }
                        }
                    }
                    drawPath(path, color = s.color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
                    // 数据点
                    s.values.forEachIndexed { i, v ->
                        if (v != null) {
                            drawCircle(color = s.color, radius = 3f, center = Offset(xAt(i), yAt(v)))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        // 横轴：最多显示 6 个标签，避免拥挤
        val step = ((labels.size + 5) / 6).coerceAtLeast(1)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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

private fun fmt(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.1f", v)
