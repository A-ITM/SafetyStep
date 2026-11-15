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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aitm.pleosapp.prototype.PrototypeUi

/** Spatial seat layout adapted from Figma S4. A vacated seat is not a safe-zone report. */
@Composable
internal fun CabinMap(s: PrototypeUi, preparing: Boolean = false) {
    val c = LocalSafetyColors.current
    val seats = s.vehicle.seats.associateBy { it.area }
    fun occupied(area: Int) = seats[area]?.occupied?.current(s.now)
    fun label(area: Int) = when (occupied(area)) {
        true -> "탑승 중"
        false -> if (area in s.occupiedSeatAreas) "자리 비움" else "빈자리"
        null -> "확인 중"
    }
    val remaining = seats.values.count { it.occupied.current(s.now) == true }
    val unknown = seats.isEmpty() || seats.values.any { it.occupied.current(s.now) == null }
    val description = seats.values.sortedBy { it.area }.joinToString { "${it.label} ${label(it.area)}" }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(SafetyStepTokens.radius)).background(c.inset).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(218.dp).height(if (preparing) 208.dp else 232.dp).semantics { contentDescription = "좌석 배치. $description" }) {
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
            // Only the driver door is read by this prototype; other doors are not invented.
            val door = s.vehicle.driverDoorOpen.current(s.now)
            drawLine(if (door == true) c.blue else c.line, p(.16f, .37f), p(if (door == true) .025f else .16f, .58f), 4.dp.toPx(), StrokeCap.Round)
            val trunk = s.vehicle.trunkOpen.current(s.now)
            drawLine(if (trunk == true) c.blue else c.line, p(.35f, if (trunk == true) .995f else .91f), p(.65f, if (trunk == true) .995f else .91f), 3.dp.toPx(), StrokeCap.Round)
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
                    drawCircle(color, sw * .15f, center - Offset(0f, sh * .14f))
                    drawRoundRect(color, center + Offset(-sw * .24f, sh * .07f), Size(sw * .48f, sh * .25f), CornerRadius(4.dp.toPx()))
                } else if (vacated) {
                    val check = Path().apply {
                        moveTo(center.x - sw * .25f, center.y); lineTo(center.x - sw * .05f, center.y + sh * .13f); lineTo(center.x + sw * .27f, center.y - sh * .16f)
                    }
                    drawPath(check, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                } else drawLine(color.copy(alpha = .6f), center - Offset(sw * .15f, 0f), center + Offset(sw * .15f, 0f), 1.5.dp.toPx())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("좌석별 이동 상태", style = SafetyStepTokens.caption, color = c.secondary)
            Text(when { remaining > 0 -> "탑승 중 ${remaining}석"; unknown -> "좌석 확인 중"; else -> "모든 좌석이 비어 있어요" },
                style = SafetyStepTokens.label, fontWeight = FontWeight.SemiBold, color = if (remaining > 0) c.blue else c.text)
            listOf(1 to "운전석", 4 to "동승석").forEach { (area, name) ->
                Text("$name · ${label(area)}", style = SafetyStepTokens.caption,
                    color = when { occupied(area) == true -> c.blue; occupied(area) == false && area in s.occupiedSeatAreas -> c.mint; else -> c.secondary })
            }
            if (preparing) {
                Text("운전석 문 · ${when (s.vehicle.driverDoorOpen.current(s.now)) { true -> "열림"; false -> "닫힘"; null -> "확인 중" }}", style = SafetyStepTokens.caption, color = c.secondary)
            } else Text("뒷좌석 · ${if (listOf(16, 32, 64).any { occupied(it) == null }) "확인 중" else "탑승 ${listOf(16, 32, 64).count { occupied(it) == true }}석"}", style = SafetyStepTokens.caption, color = c.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(c.blue to "탑승", c.mint to "자리 비움", c.secondary to "빈자리").forEach { (color, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                        Text(name, style = SafetyStepTokens.caption, color = c.secondary)
                    }
                }
            }
        }
    }
}
