package com.frogweather.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frogweather.data.CityImagePromptGenerator
import com.frogweather.data.CityImageRepository
import com.frogweather.data.WeatherScenario
import com.frogweather.ui.settings.CityImageSettingsActivity

@Composable
fun MissingImageBanner(
    missingCount: Int,
    highlightedScenario: WeatherScenario? = null,
    latitude: Double? = null,
    longitude: Double? = null,
    cityName: String? = null,
    country: String? = null,
    missingScenarios: List<WeatherScenario> = emptyList()
) {
    val context = LocalContext.current
    var showPromptDialog by remember { mutableStateOf(false) }

    val effectiveMissingScenarios = remember(missingScenarios, latitude, longitude, missingCount) {
        if (missingScenarios.isNotEmpty()) {
            missingScenarios
        } else if (latitude != null && longitude != null) {
            val repo = CityImageRepository.getInstance(context)
            repo.findCityByLocation(latitude, longitude)?.let { repo.getMissingImages(it.id) }
                ?: WeatherScenario.entries.toList()
        } else {
            WeatherScenario.entries.toList()
        }
    }

    val cityLabel = remember(cityName, country) {
        CityImagePromptGenerator.formatCityLabel(
            cityName = cityName ?: "Unbekannte Stadt",
            country = country
        )
    }

    Column(
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
                    cityName?.takeIf { it.isNotBlank() }?.let { putExtra("current_city_name", it) }
                    country?.takeIf { it.isNotBlank() }?.let { putExtra("current_city_country", it) }
                }
                context.startActivity(intent)
            }
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Fehlende Bilder",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$missingCount Wetterszenarien haben noch kein Bild. " +
                        if (highlightedScenario != null) {
                            "Tippe um ${highlightedScenario.displayName} hinzuzufügen."
                        } else {
                            "Tippe um Bilder hinzuzufügen."
                        },
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { showPromptDialog = true },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFF1C2733),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color(0xFF4FC3F7),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "KI-Prompts",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    }

    if (showPromptDialog) {
        PromptGeneratorDialog(
            cityLabel = cityLabel,
            missingScenarios = effectiveMissingScenarios,
            highlightedScenario = highlightedScenario,
            onDismiss = { showPromptDialog = false }
        )
    }
}
