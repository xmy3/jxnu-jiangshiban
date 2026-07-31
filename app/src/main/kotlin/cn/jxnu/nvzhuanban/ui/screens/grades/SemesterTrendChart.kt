package cn.jxnu.nvzhuanban.ui.screens.grades

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.jxnu.nvzhuanban.data.model.SemesterSummary
import cn.jxnu.nvzhuanban.ui.theme.AppShape

/**
 * 跨学期「加权平均标准分」趋势折线图。
 *
 * 数据完全复用成绩页已算好的 [SemesterSummary.gpa]（每学期加权平均标准分），**不发任何网络请求**。
 * 江师大「标准分」是按排名归一化的 Z-score，**可正可负**，所以 Y 轴按数据实际 min..max 取值域，
 * 不做 0 基兜底；当 0 落在值域内时画一条淡淡的零基准线帮助判读正负。
 *
 * 措辞纪律：教务口径叫「标准分」，全程不得出现「GPA/绩点」。
 *
 * 传入的 [semesters] 沿用成绩页顺序（最新学期在前），内部反转为时间正序（左旧右新）。
 * 少于 2 个学期不构成趋势，调用方应先判断再决定是否渲染。
 */
@Composable
internal fun SemesterTrendCard(
    semesters: List<SemesterSummary>,
    modifier: Modifier = Modifier,
) {
    // 时间正序：成绩页最新在前，折线要左旧右新
    val points = remember(semesters) {
        semesters.asReversed().map { TrendPoint(abbreviateSemester(it.semester), it.gpa) }
    }
    if (points.size < 2) return

    val lineColor = MaterialTheme.colorScheme.primary
    val fillTop = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val fillBottom = MaterialTheme.colorScheme.primary.copy(alpha = 0f)
    val zeroColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val axisLabelStyle = remember(labelColor) { TextStyle(color = labelColor, fontSize = 9.sp) }
    val valueLabelStyle = remember(lineColor) {
        TextStyle(color = lineColor, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShape.listItem,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = "加权平均标准分趋势",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .padding(top = 12.dp),
            ) {
                drawTrend(
                    points = points,
                    lineColor = lineColor,
                    fillBrush = Brush.verticalGradient(listOf(fillTop, fillBottom)),
                    zeroColor = zeroColor,
                    measurer = measurer,
                    axisLabelStyle = axisLabelStyle,
                    valueLabelStyle = valueLabelStyle,
                )
            }
        }
    }
}

private data class TrendPoint(val label: String, val value: Float)

private fun DrawScope.drawTrend(
    points: List<TrendPoint>,
    lineColor: androidx.compose.ui.graphics.Color,
    fillBrush: Brush,
    zeroColor: androidx.compose.ui.graphics.Color,
    measurer: TextMeasurer,
    axisLabelStyle: TextStyle,
    valueLabelStyle: TextStyle,
) {
    val values = points.map { it.value }
    val rawMin = values.min()
    val rawMax = values.max()
    // 值域留白：全相等时上下各撑 1，避免除零并让平线居中
    val span = (rawMax - rawMin)
    val pad = if (span < 0.0001f) 1f else span * 0.18f
    val top = rawMax + pad
    val bottom = rawMin - pad
    val range = (top - bottom).coerceAtLeast(0.0001f)

    val leftPad = 6.dp.toPx()
    val rightPad = 6.dp.toPx()
    val bottomAxis = 16.dp.toPx()   // x 轴学期标签
    val topInset = 14.dp.toPx()     // 顶部给最新点的数值标签留位
    val plotW = size.width - leftPad - rightPad
    val plotH = size.height - bottomAxis - topInset

    fun px(i: Int) = leftPad + if (points.size == 1) plotW / 2 else plotW * i / (points.size - 1)
    fun py(v: Float) = topInset + plotH * (top - v) / range

    // 零基准线（仅当 0 在值域内）
    if (bottom < 0f && top > 0f) {
        val zeroY = py(0f)
        drawLine(
            color = zeroColor,
            start = Offset(leftPad, zeroY),
            end = Offset(leftPad + plotW, zeroY),
            strokeWidth = 1.dp.toPx(),
        )
    }

    val offsets = points.mapIndexed { i, p -> Offset(px(i), py(p.value)) }

    // 折线下方渐变填充
    val fillPath = Path().apply {
        moveTo(offsets.first().x, topInset + plotH)
        offsets.forEach { lineTo(it.x, it.y) }
        lineTo(offsets.last().x, topInset + plotH)
        close()
    }
    drawPath(path = fillPath, brush = fillBrush)

    // 折线
    val linePath = Path().apply {
        moveTo(offsets.first().x, offsets.first().y)
        offsets.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(
        path = linePath,
        color = lineColor,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
    )

    // 数据点
    offsets.forEach { o ->
        drawCircle(color = lineColor, radius = 3.dp.toPx(), center = o)
    }

    // x 轴学期标签（居中于各点下方，超界则贴边）
    points.forEachIndexed { i, p ->
        val layout = measurer.measure(p.label, axisLabelStyle)
        val cx = (offsets[i].x - layout.size.width / 2f)
            .coerceIn(0f, size.width - layout.size.width)
        drawText(layout, topLeft = Offset(cx, size.height - layout.size.height))
    }

    // 仅给首尾点标数值，避免拥挤
    listOf(0, points.lastIndex).distinct().forEach { i ->
        val label = "%.2f".format(points[i].value)
        val layout = measurer.measure(label, valueLabelStyle)
        val cx = (offsets[i].x - layout.size.width / 2f)
            .coerceIn(0f, size.width - layout.size.width)
        val cy = (offsets[i].y - layout.size.height - 2.dp.toPx()).coerceAtLeast(0f)
        drawText(layout, topLeft = Offset(cx, cy))
    }
}

/** "25-26第1学期" -> "25-26·1"；解析不出就退化取前 7 字。 */
private fun abbreviateSemester(raw: String): String {
    val m = Regex("""(\d{2,4})-(\d{2,4}).*?(\d)""").find(raw)
    return if (m != null) "${m.groupValues[1]}-${m.groupValues[2]}·${m.groupValues[3]}"
    else raw.take(7)
}
