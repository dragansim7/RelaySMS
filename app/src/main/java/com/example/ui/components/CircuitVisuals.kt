package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.remote.OverallSyncStatus
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalRelayExtras
import com.example.ui.theme.SpaceGroteskFamily

/**
 * Subtle procedural PCB trace overlay drawn purely from container DrawScope dimensions.
 * Renders orthogonal & 45-degree etched traces and precision solder vias on the right edge.
 */
@Composable
fun Modifier.circuitBoardAccent(
    enabled: Boolean = LocalRelayExtras.current.showCircuitOverlay,
    traceColor: Color = LocalRelayExtras.current.circuitTrace,
    accentTraceColor: Color = LocalRelayExtras.current.circuitAccentTrace,
    viaColor: Color = LocalRelayExtras.current.circuitViaGlow,
): Modifier {
    if (!enabled) return this
    return this.drawBehind {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@drawBehind

        val baseStroke = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val accentStroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        // Subtle dark etched bus trace along top-right
        val p1 = Path().apply {
            moveTo(w * 0.60f, 0f)
            lineTo(w * 0.66f, h * 0.22f)
            lineTo(w * 0.86f, h * 0.22f)
            lineTo(w * 0.92f, h * 0.42f)
            lineTo(w, h * 0.42f)
        }
        drawPath(p1, color = traceColor, style = baseStroke)

        // Parallel secondary etched bus
        val p2 = Path().apply {
            moveTo(w * 0.68f, 0f)
            lineTo(w * 0.72f, h * 0.14f)
            lineTo(w * 0.90f, h * 0.14f)
            lineTo(w * 0.96f, h * 0.30f)
            lineTo(w, h * 0.30f)
        }
        drawPath(p2, color = traceColor.copy(alpha = 0.7f), style = baseStroke)

        // Luminous amber primary signal pathway
        val p3 = Path().apply {
            moveTo(w * 0.54f, h)
            lineTo(w * 0.63f, h * 0.72f)
            lineTo(w * 0.80f, h * 0.72f)
            lineTo(w * 0.87f, h * 0.56f)
            lineTo(w * 0.95f, h * 0.56f)
        }
        drawPath(p3, color = accentTraceColor, style = accentStroke)

        // Precision via solder rings
        val via1 = Offset(w * 0.95f, h * 0.56f)
        val via2 = Offset(w * 0.86f, h * 0.22f)
        val via3 = Offset(w * 0.63f, h * 0.72f)

        drawCircle(color = viaColor.copy(alpha = 0.22f), radius = 6.dp.toPx(), center = via1)
        drawCircle(color = viaColor, radius = 2.6.dp.toPx(), center = via1, style = Stroke(width = 1.4.dp.toPx()))
        drawCircle(color = traceColor, radius = 2.2.dp.toPx(), center = via2, style = Stroke(width = 1.2.dp.toPx()))
        drawCircle(color = viaColor.copy(alpha = 0.7f), radius = 2.dp.toPx(), center = via3)
    }
}

@Composable
fun StatusSignalDot(
    active: Boolean,
    modifier: Modifier = Modifier,
    dotSize: Dp = 8.dp,
) {
    val extras = LocalRelayExtras.current
    val baseColor = if (active) extras.liveSignal else extras.inactiveSignal

    val infinite = rememberInfiniteTransition(label = "signal_dot_pulse")
    val haloAlpha by infinite.animateFloat(
        initialValue = if (active) 0.40f else 0.16f,
        targetValue = if (active) 0.10f else 0.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo_alpha",
    )
    val haloScale by infinite.animateFloat(
        initialValue = 1.6f,
        targetValue = if (active) 2.2f else 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo_scale",
    )

    Box(
        modifier = modifier
            .size(dotSize * 2.2f)
            .semantics {
                contentDescription = if (active) "Active line" else "Inactive line"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = dotSize.toPx() / 2f
            drawCircle(
                color = baseColor.copy(alpha = haloAlpha),
                radius = radius * haloScale,
            )
            drawCircle(
                color = baseColor,
                radius = radius,
            )
        }
    }
}

@Composable
fun SyncHealthPill(
    status: OverallSyncStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = LocalRelayExtras.current
    val colorScheme = MaterialTheme.colorScheme

    val (label, tint, icon) = when (status) {
        OverallSyncStatus.SYNCING -> Triple("SYNCING", colorScheme.primary, Icons.Rounded.Sync)
        OverallSyncStatus.LIVE -> Triple("LIVE", extras.liveSignal, Icons.Rounded.CloudDone)
        OverallSyncStatus.DEGRADED -> Triple("DEGRADED", extras.inactiveSignal, Icons.Rounded.WarningAmber)
        OverallSyncStatus.IDLE -> Triple("READY", colorScheme.onSurfaceVariant, Icons.Rounded.CloudDone)
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .testTag("sync_health_pill"),
        shape = CircleShape,
        color = colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun OtpCodeChip(
    code: String,
    copied: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extras = LocalRelayExtras.current

    val containerColor by animateColorAsState(
        targetValue = if (copied) extras.liveSignal.copy(alpha = 0.18f) else colorScheme.primaryContainer,
        animationSpec = tween(200),
        label = "otp_bg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (copied) extras.liveSignal else colorScheme.primary.copy(alpha = 0.75f),
        animationSpec = tween(200),
        label = "otp_border",
    )
    val contentColor by animateColorAsState(
        targetValue = if (copied) extras.liveSignal else colorScheme.primary,
        animationSpec = tween(200),
        label = "otp_content",
    )

    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("otp_copy_chip_$code"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (copied) "COPIED" else "OTP",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.85f),
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = code,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            letterSpacing = 1.4.sp,
            color = if (copied) contentColor else colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
            contentDescription = if (copied) "Code copied" else "Copy code $code",
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
fun RelayBrandMark(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(colorScheme.primaryContainer)
                .border(1.dp, colorScheme.primary.copy(alpha = 0.65f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(15.dp)) {
                val p = Path().apply {
                    moveTo(size.width * 0.12f, size.height * 0.65f)
                    lineTo(size.width * 0.36f, size.height * 0.25f)
                    lineTo(size.width * 0.56f, size.height * 0.72f)
                    lineTo(size.width * 0.86f, size.height * 0.25f)
                }
                drawPath(
                    path = p,
                    color = colorScheme.primary,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawCircle(
                    color = colorScheme.primary,
                    radius = 1.8.dp.toPx(),
                    center = Offset(size.width * 0.86f, size.height * 0.25f),
                )
            }
        }
        Text(
            text = "Relay",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 19.sp,
            letterSpacing = (-0.4).sp,
            color = colorScheme.onSurface,
        )
    }
}
