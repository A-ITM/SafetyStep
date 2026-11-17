package io.github.aitm.pleosapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aitm.pleosapp.prototype.PrototypeUi

/** Compact spatial observation. Empty or unknown seats never establish safe arrival. */
@Composable
internal fun CabinMap(s: PrototypeUi, @Suppress("UNUSED_PARAMETER") preparing: Boolean = false) {
    val c = LocalSafetyColors.current
    val seats = s.vehicle.seats.associateBy { it.area }
    val expected = listOf(1 to "운전석", 4 to "동승석", 16 to "뒷좌석 왼쪽", 32 to "뒷좌석 가운데", 64 to "뒷좌석 오른쪽")
    fun occupied(area: Int) = seats[area]?.occupied?.current(s.now)
    fun label(area: Int) = when (occupied(area)) {
        true -> "감지됨"
        false -> if (area in s.occupiedSeatAreas) "자리 비움" else "감지 안 됨"
        null -> "확인 필요"
    }
    fun names(areas: List<Pair<Int, String>>) = areas.joinToString(" · ") { it.second }
    val remaining = expected.filter { occupied(it.first) == true }
    val unknown = expected.filter { occupied(it.first) == null }
    val observedSeatsEmpty = seats.isNotEmpty() && seats.values.all { it.occupied.current(s.now) == false }
    val passengerRemains = occupied(1) == false && remaining.any { it.first != 1 }
    val description = expected.joinToString { (area, name) -> "$name ${label(area)}" }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("cabin-map")) {
        val diagramWidth = if (maxWidth < 560.dp) 128.dp else 144.dp
        Row(Modifier.fillMaxWidth().heightIn(min = 180.dp).clip(RoundedCornerShape(SafetyStepTokens.radius))
            .background(c.inset).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(diagramWidth).height(156.dp).semantics { contentDescription = "좌석 배치. $description" }) {
                val w = size.width; val h = size.height
                fun p(x: Float, y: Float) = Offset(w * x, h * y)
                val body = Path().apply {
                    moveTo(w * .42f, h * .04f); lineTo(w * .58f, h * .04f)
                    cubicTo(w * .75f, h * .04f, w * .82f, h * .19f, w * .82f, h * .32f)
                    lineTo(w * .82f, h * .78f)
                    cubicTo(w * .82f, h * .93f, w * .69f, h * .98f, w * .5f, h * .98f)
                    cubicTo(w * .31f, h * .98f, w * .18f, h * .93f, w * .18f, h * .78f)
                    lineTo(w * .18f, h * .32f)
                    cubicTo(w * .18f, h * .19f, w * .25f, h * .04f, w * .42f, h * .04f); close()
                }
                drawPath(body, c.panel)
                drawPath(body, c.line, style = Stroke(2.dp.toPx()))
                val glass = Path().apply {
                    moveTo(w * .27f, h * .26f); quadraticTo(w * .5f, h * .17f, w * .73f, h * .26f)
                    lineTo(w * .68f, h * .32f); quadraticTo(w * .5f, h * .27f, w * .32f, h * .32f); close()
                }
                drawPath(glass, c.line)
                val positions = listOf(Triple(1, .28f, .38f), Triple(4, .56f, .38f), Triple(16, .28f, .65f), Triple(32, .45f, .65f), Triple(64, .62f, .65f))
                positions.forEach { (area, x, y) ->
                    val value = occupied(area)
                    val vacated = value == false && area in s.occupiedSeatAreas
                    val color = when { value == true -> c.blue; vacated -> c.mint; else -> c.secondary }
                    val sw = w * if (area <= 4) .16f else .10f
                    val sh = h * if (area <= 4) .19f else .15f
                    val origin = p(x, y)
                    drawRoundRect(if (value == true || vacated) color.copy(alpha = .16f) else c.inset, origin, Size(sw, sh), CornerRadius(8.dp.toPx()))
                    drawRoundRect(color.copy(alpha = if (value == true || vacated) 1f else .5f), origin, Size(sw, sh), CornerRadius(8.dp.toPx()),
                        style = Stroke(1.5.dp.toPx(), pathEffect = if (value == null) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null))
                    val center = origin + Offset(sw / 2, sh / 2)
                    if (value == true) {
                        // A seat signal identifies neither a person nor a safe location.
                        drawCircle(color, sw * .17f, center)
                    } else if (vacated) {
                        val check = Path().apply {
                            moveTo(center.x - sw * .25f, center.y); lineTo(center.x - sw * .05f, center.y + sh * .13f); lineTo(center.x + sw * .27f, center.y - sh * .16f)
                        }
                        drawPath(check, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                    } else drawLine(color.copy(alpha = .6f), center - Offset(sw * .15f, 0f), center + Offset(sw * .15f, 0f), 1.5.dp.toPx())
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (s.vehicle.sample) Text("샘플 좌석", style = SafetyStepTokens.caption, color = c.secondary)
                Text(when {
                    passengerRemains -> "동승 좌석 확인 필요"
                    remaining.isNotEmpty() -> "감지된 좌석"
                    observedSeatsEmpty -> "관측 좌석 비움"
                    else -> "좌석 확인 필요"
                }, style = SafetyStepTokens.label, fontWeight = FontWeight.SemiBold,
                    color = if (passengerRemains) c.amber else if (remaining.isNotEmpty()) c.blue else c.text,
                    modifier = Modifier.testTag("bitbox-exit"))
                if (remaining.isNotEmpty()) Text(names(remaining), style = SafetyStepTokens.label,
                    color = if (passengerRemains) c.amber else c.blue, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (unknown.isNotEmpty()) Text("미확인 좌석 · ${names(unknown)}", style = SafetyStepTokens.caption,
                    color = c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (remaining.isEmpty() && unknown.isEmpty()) Text("관측 좌석에서 감지되지 않았어요", style = SafetyStepTokens.caption, color = c.secondary)
            }
        }
    }
}
