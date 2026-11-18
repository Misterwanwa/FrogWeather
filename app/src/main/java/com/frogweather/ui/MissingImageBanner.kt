package com.frogweather.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frogweather.data.WeatherScenario
import com.frogweather.ui.settings.CityImageSettingsActivity

@Composable
fun MissingImageBanner(
    missingCount: Int,
    highlightedScenario: WeatherScenario? = null,
    latitude: Double? = null,
    longitude: Double? = null
) {
    val context = LocalContext.current
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFA726), RoundedCornerShape(12.dp))
            .clickable {
                val intent = Intent(context, CityImageSettingsActivity::class.java).apply {
                    highlightedScenario?.let {
                        putExtra("highlight_scenario", it.key)
                    }
                    latitude?.let { putExtra("current_lat", it) }
                    longitude?.let { putExtra("current_lon", it) }
                }
                context.startActivity(intent)
            }
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Fehlende Bilder",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    text = "$missingCount Wetterszenarien haben noch kein Bild. " +
                           if (highlightedScenario != null) "Tippe um ${highlightedScenario.displayName} hinzuzufügen."
                           else "Tippe um Bilder hinzuzufügen.",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp
                )
            }
        }
    }
}
