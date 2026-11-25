package com.frogweather.ui.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frogweather.briefing.BriefingCardItem
import com.frogweather.briefing.ComposedBriefing
import com.frogweather.ui.theme.FrogTheme

private val StatusAlertBorder = Color(0xFFFFB74D)

@Composable
fun BriefingCompactCard(
    briefing: ComposedBriefing,
    morningTimeLabel: String,
    onOpenPlanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = FrogTheme.palette

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.tileSurface)
            .border(1.dp, palette.dividerColor, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Tages-Briefing",
                        color = palette.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${briefing.card.subtitle} • $morningTimeLabel Uhr",
                        color = palette.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Row(
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.tileSurfaceElevated)
                    .border(1.dp, palette.accent, RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenPlanner)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Planer",
                    color = palette.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "Zum Planer",
                    tint = palette.accent,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        briefing.card.items.forEach { item ->
            BriefingCompactRow(item = item)
        }
    }
}

@Composable
private fun BriefingCompactRow(item: BriefingCardItem) {
    val palette = FrogTheme.palette
    val borderColor = if (item.isAlert) StatusAlertBorder else palette.dividerColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.tileSurfaceElevated)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = iconForBriefingKey(item.key),
            contentDescription = null,
            tint = if (item.isAlert) StatusAlertBorder else palette.accent,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                color = if (item.isAlert) StatusAlertBorder else palette.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.text,
                color = palette.textPrimary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

internal fun iconForBriefingKey(key: String): ImageVector = when (key) {
    "weather" -> Icons.Default.WbSunny
    "clothing" -> Icons.Default.Checkroom
    "sport" -> Icons.Default.DirectionsRun
    "calendar" -> Icons.Default.Event
    "route" -> Icons.Default.DirectionsCar
    else -> Icons.Default.NotificationsActive
}
