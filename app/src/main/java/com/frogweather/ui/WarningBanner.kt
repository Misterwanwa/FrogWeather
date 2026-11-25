package com.frogweather.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frogweather.service.WarningSeverity
import com.frogweather.service.WeatherWarning
import com.frogweather.ui.theme.FrogTheme

@Composable
fun WarningBanner(
    warning: WeatherWarning,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    val backgroundColor = Color(warning.severity.color)
    val textColor = Color.White
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warnung",
                    tint = textColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "${warning.source.badgeText}-WARNUNG: ${warning.title}",
                        color = textColor,
                        fontSize = 13.sp,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "${warning.severity.germanName} | ${warning.area} | ${warning.startTime} - ${warning.endTime}",
                        color = textColor.copy(alpha = 0.9f),
                        fontSize = 11.sp
                    )
                }
            }
            
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Einklappen" else "Ausklappen",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
        
        // Expanded details
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = warning.description,
                    color = textColor.copy(alpha = 0.95f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                
                warning.instruction?.let { instruction ->
                    if (instruction.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Empfohlene Maßnahmen: $instruction",
                            color = textColor.copy(alpha = 0.9f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WarningBanners(
    warnings: List<WeatherWarning>,
    modifier: Modifier = Modifier
) {
    if (warnings.isEmpty()) return
    
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Show only the most severe warning first, or all if desired
        val mostSevere = warnings.firstOrNull()
        mostSevere?.let { 
            WarningBanner(warning = it)
        }
        
        // Optionally show count of additional warnings
        if (warnings.size > 1) {
            Text(
                text = "+ ${warnings.size - 1} weitere Warnung(en)",
                color = FrogTheme.palette.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
