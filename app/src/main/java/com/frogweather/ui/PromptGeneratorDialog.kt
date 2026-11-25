package com.frogweather.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.frogweather.data.CityImagePromptGenerator
import com.frogweather.data.WeatherScenario
import com.frogweather.ui.theme.FrogTheme

@Composable
fun PromptGeneratorDialog(
    cityLabel: String,
    missingScenarios: List<WeatherScenario>,
    highlightedScenario: WeatherScenario? = null,
    onDismiss: () -> Unit
) {
    val palette = FrogTheme.palette
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var additionalLandmarks by remember { mutableStateOf("") }

    val orderedScenarios = remember(missingScenarios, highlightedScenario) {
        val unique = missingScenarios.distinct()
        if (highlightedScenario != null && highlightedScenario in unique) {
            listOf(highlightedScenario) + unique.filterNot { it == highlightedScenario }
        } else {
            unique
        }
    }

    val prompts = remember(cityLabel, orderedScenarios, additionalLandmarks) {
        CityImagePromptGenerator.buildPromptsForMissing(
            cityLabel = cityLabel,
            missingScenarios = orderedScenarios,
            additionalLandmarks = additionalLandmarks
        )
    }

    val combinedPromptsText = remember(prompts) {
        CityImagePromptGenerator.formatAllPrompts(prompts)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(20.dp),
            color = palette.screenBackground,
            elevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Title Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "KI-Bildprompts ($cityLabel)",
                                color = palette.textPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${prompts.size} fehlende Szenarien • Copy & Paste in Bild-KI",
                                color = palette.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Schließen",
                            tint = palette.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Optional ADDITIONAL_LANDMARKS input
                OutlinedTextField(
                    value = additionalLandmarks,
                    onValueChange = { additionalLandmarks = it },
                    label = { Text("ADDITIONAL_LANDMARKS (optional, leer = NONE)") },
                    placeholder = { Text("z. B. Fernsehturm, Flussufer, Altstadtbrücke") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = palette.textPrimary,
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.dividerColor,
                        focusedLabelColor = palette.accent,
                        unfocusedLabelColor = palette.textSecondary,
                        placeholderColor = palette.textSecondary.copy(alpha = 0.6f),
                        cursorColor = palette.accent
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Action row: Copy All + Share
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (combinedPromptsText.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(combinedPromptsText))
                                Toast.makeText(
                                    context,
                                    "Alle ${prompts.size} Prompts kopiert",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        enabled = prompts.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = palette.accent,
                            contentColor = palette.screenBackground
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Alle kopieren", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = {
                            if (combinedPromptsText.isNotEmpty()) {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "FrogWeather KI-Prompts: $cityLabel")
                                    putExtra(Intent.EXTRA_TEXT, combinedPromptsText)
                                }
                                context.startActivity(
                                    Intent.createChooser(sendIntent, "Prompts teilen")
                                )
                            }
                        },
                        enabled = prompts.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = palette.tileSurface,
                            contentColor = palette.textPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Teilen", fontSize = 13.sp, color = palette.textPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Prompts list
                if (prompts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.tileSurface)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Alle 13 Wetterszenarien für $cityLabel haben bereits ein Bild.",
                            color = palette.textSecondary,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(prompts, key = { it.scenario.key }) { entry ->
                            val isCurrent = entry.scenario == highlightedScenario
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isCurrent) palette.tileSurfaceElevated else palette.tileSurface)
                                    .border(
                                        width = if (isCurrent) 1.5.dp else 1.dp,
                                        color = if (isCurrent) palette.accent else palette.dividerColor,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(text = entry.scenario.emoji, fontSize = 18.sp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${entry.scenario.displayName} • ${entry.typicalTempCelsius}° C",
                                            color = palette.textPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (isCurrent) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(palette.accent.copy(alpha = 0.22f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "Aktuell",
                                                    color = palette.accent,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(entry.prompt))
                                            Toast.makeText(
                                                context,
                                                "Prompt für ${entry.scenario.displayName} kopiert",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Prompt kopieren",
                                            tint = palette.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = entry.prompt,
                                    color = palette.textSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
