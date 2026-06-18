package be.heyman.android.etymoclan.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.heyman.android.etymoclan.data.gs1voc.KnowledgeFrame
import be.heyman.android.etymoclan.data.gs1voc.KnowledgeSlot
import be.heyman.android.etymoclan.data.gs1voc.KnowledgeTheme
import be.heyman.android.etymoclan.data.gs1voc.SlotStatus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Affiche le graphe de connaissance d'un membre :
 *   - un nœud central avec l'anneau de complétion (%),
 *   - les thèmes GS1 disposés en couronne, colorés selon le statut de leurs slots,
 *   - au clic d'un thème : la liste de ses slots (champs) avec valeur/statut.
 */
@Composable
fun MemberKnowledgeGraphScreen(
    frame: KnowledgeFrame,
    modifier: Modifier = Modifier,
    onSlotClick: (KnowledgeSlot) -> Unit = {}
) {
    var selected by remember { mutableStateOf<KnowledgeTheme?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(frame.gs1ClassLabel, style = MaterialTheme.typography.titleLarge)
        Text(
            "${frame.filledSlots} / ${frame.totalSlots} champs documentés · ${frame.completionPercent}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        KnowledgeGraphRadial(
            frame = frame,
            selected = selected,
            onThemeSelected = { selected = if (selected == it) null else it }
        )

        Spacer(Modifier.height(12.dp))

        val theme = selected
        if (theme == null) {
            StatusLegend()
        } else {
            ThemeSlotList(frame, theme, onSlotClick)
        }
    }
}

/* ----------------------------- Graphe radial ----------------------------- */

@Composable
private fun KnowledgeGraphRadial(
    frame: KnowledgeFrame,
    selected: KnowledgeTheme?,
    onThemeSelected: (KnowledgeTheme) -> Unit
) {
    val themes = frame.byTheme.keys.toList()
    val density = LocalDensity.current
    val nodeSize = 88.dp
    val centerSize = 100.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        val sizePx = with(density) { maxWidth.toPx() }
        val center = Offset(sizePx / 2f, sizePx / 2f)
        val ringRadius = sizePx * 0.16f
        val orbit = sizePx * 0.34f
        val nodeHalfPx = with(density) { nodeSize.toPx() } / 2f
        val centerHalfPx = with(density) { centerSize.toPx() } / 2f

        // Positions polaires (départ en haut, sens horaire)
        fun posFor(i: Int): Offset {
            val a = (-90.0 + i * 360.0 / themes.size) * PI / 180.0
            return center + Offset((orbit * cos(a)).toFloat(), (orbit * sin(a)).toFloat())
        }

        // 1) Connecteurs + anneau de complétion (Canvas)
        Canvas(Modifier.fillMaxSize()) {
            themes.forEachIndexed { i, t ->
                drawLine(
                    color = frame.statusOf(t).color.copy(alpha = 0.45f),
                    start = center,
                    end = posFor(i),
                    strokeWidth = 2f
                )
            }
            // anneau de fond
            drawCircle(
                color = Color(0x33888780),
                radius = ringRadius,
                center = center,
                style = Stroke(width = 10f)
            )
            // arc de progression
            val sweep = 360f * frame.completionPercent / 100f
            drawArc(
                color = Color(0xFFBA7517),
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                size = Size(ringRadius * 2, ringRadius * 2),
                style = Stroke(width = 10f, cap = StrokeCap.Round)
            )
        }

        // 2) Nœud central
        CenterNode(
            frame = frame,
            size = centerSize,
            modifier = Modifier.offset {
                IntOffset((center.x - centerHalfPx).roundToInt(), (center.y - centerHalfPx).roundToInt())
            }
        )

        // 3) Nœuds-thèmes
        themes.forEachIndexed { i, t ->
            val p = posFor(i)
            ThemeNode(
                theme = t,
                filled = frame.byTheme[t].orEmpty().count { it.isFilled },
                total = frame.byTheme[t].orEmpty().size,
                status = frame.statusOf(t),
                selected = selected == t,
                size = nodeSize,
                modifier = Modifier
                    .offset {
                        IntOffset((p.x - nodeHalfPx).roundToInt(), (p.y - nodeHalfPx).roundToInt())
                    }
                    .clickable { onThemeSelected(t) }
            )
        }
    }
}

@Composable
private fun CenterNode(frame: KnowledgeFrame, size: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFFD85A30).copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${frame.completionPercent}%", fontSize = 22.sp, fontWeight = FontWeight.Medium,
                color = Color(0xFF993C1D))
            Text("complété", fontSize = 11.sp, color = Color(0xFF993C1D))
        }
    }
}

@Composable
private fun ThemeNode(
    theme: KnowledgeTheme,
    filled: Int,
    total: Int,
    status: SlotStatus,
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(status.color.copy(alpha = if (selected) 0.35f else 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(theme.labelFr, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, color = darken(status.color))
            Text("$filled / $total", fontSize = 11.sp, color = darken(status.color))
        }
    }
}

/* ----------------------------- Détail thème ------------------------------ */

@Composable
private fun ThemeSlotList(
    frame: KnowledgeFrame,
    theme: KnowledgeTheme,
    onSlotClick: (KnowledgeSlot) -> Unit
) {
    val slots = frame.byTheme[theme].orEmpty().sortedByDescending { it.isFilled }
    Text(theme.labelFr, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(slots) { slot -> SlotRow(slot) { onSlotClick(slot) } }
    }
}

@Composable
private fun SlotRow(slot: KnowledgeSlot, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(10.dp).clip(CircleShape).background(slot.status.color)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(slot.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    slot.value ?: slot.formatHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusChip(slot.status, slot.sourceCount)
        }
    }
}

@Composable
private fun StatusChip(status: SlotStatus, sources: Int) {
    val label = if (status == SlotStatus.EMPTY) status.labelFr else "${status.labelFr} ($sources)"
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(status.color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, fontSize = 11.sp, color = darken(status.color))
    }
}

/* ------------------------------- Légende --------------------------------- */

@Composable
private fun StatusLegend() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        SlotStatus.entries.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(s.color))
                Spacer(Modifier.width(4.dp))
                Text(s.labelFr, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Assombrit une couleur de statut pour un texte lisible sur fond teinté. */
private fun darken(c: Color): Color = Color(
    red = c.red * 0.55f, green = c.green * 0.55f, blue = c.blue * 0.55f, alpha = 1f
)
