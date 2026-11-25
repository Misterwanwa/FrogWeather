package com.frogweather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.frogweather.analysis.SportType
import com.frogweather.data.AppThemeId
import com.frogweather.data.FavoriteLocation
import com.frogweather.ui.analysis.SportPickerDialog
import com.frogweather.ui.theme.FrogTheme
import com.frogweather.ui.theme.paletteForTheme
import kotlin.math.abs

enum class SettingsSheetTab {
    FAVORITES,
    THEMES
}

@Composable
fun FavoritesAndSettingsSheet(
    initialTab: SettingsSheetTab = SettingsSheetTab.FAVORITES,
    favorites: List<FavoriteLocation>,
    activeFavoriteId: String?,
    currentLocationName: String?,
    currentCountry: String?,
    currentLat: Double?,
    currentLon: Double?,
    selectedTheme: AppThemeId,
    onSaveCurrentLocation: () -> Unit,
    onSelectFavorite: (FavoriteLocation) -> Unit,
    onRemoveFavorite: (String) -> Unit,
    onSelectTheme: (AppThemeId) -> Unit,
    onOpenCityImageSettings: () -> Unit,
    onDismiss: () -> Unit,
    selectedSport: SportType? = null,
    onSelectSport: ((SportType) -> Unit)? = null,
    briefingTimeLabel: String? = null,
    onOpenBriefingSettings: (() -> Unit)? = null
) {
    val palette = FrogTheme.palette
    var activeTab by remember(initialTab) { mutableStateOf(initialTab) }
    var showSportPicker by remember { mutableStateOf(false) }

    val isCurrentAlreadySaved = remember(favorites, currentLat, currentLon) {
        if (currentLat == null || currentLon == null) {
            false
        } else {
            favorites.any {
                abs(it.latitude - currentLat) <= 0.01 &&
                    abs(it.longitude - currentLon) <= 0.01
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.screenBackground.copy(alpha = 0.55f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.84f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {},
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = palette.screenBackground,
                elevation = 12.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    // Drag handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(42.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.dividerColor)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (activeTab == SettingsSheetTab.FAVORITES) {
                                "Gespeicherte Orte"
                            } else {
                                "App-Themes & Einstellungen"
                            },
                            color = palette.textPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Schließen",
                                tint = palette.textSecondary
                            )
                        }
                    }

                    // Segmented Tab Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.tileSurface)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SheetTabPill(
                            label = "Favoriten (${favorites.size})",
                            icon = Icons.Default.Star,
                            selected = activeTab == SettingsSheetTab.FAVORITES,
                            onClick = { activeTab = SettingsSheetTab.FAVORITES },
                            modifier = Modifier.weight(1f)
                        )
                        SheetTabPill(
                            label = "App-Themes",
                            icon = Icons.Default.Palette,
                            selected = activeTab == SettingsSheetTab.THEMES,
                            onClick = { activeTab = SettingsSheetTab.THEMES },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Body
                    Box(modifier = Modifier.weight(1f)) {
                        if (activeTab == SettingsSheetTab.FAVORITES) {
                            FavoritesSection(
                                favorites = favorites,
                                activeFavoriteId = activeFavoriteId,
                                currentLocationName = currentLocationName,
                                currentCountry = currentCountry,
                                currentLat = currentLat,
                                currentLon = currentLon,
                                isCurrentAlreadySaved = isCurrentAlreadySaved,
                                onSaveCurrentLocation = onSaveCurrentLocation,
                                onSelectFavorite = onSelectFavorite,
                                onRemoveFavorite = onRemoveFavorite
                            )
                        } else {
                            ThemesSection(
                                selectedTheme = selectedTheme,
                                onSelectTheme = onSelectTheme
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Divider(color = palette.dividerColor)
                    Spacer(modifier = Modifier.height(10.dp))

                    if (selectedSport != null && onSelectSport != null) {
                        OutlinedButton(
                            onClick = { showSportPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                backgroundColor = palette.tileSurface,
                                contentColor = palette.accent
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Sportart: ${selectedSport.displayName}",
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "Ändern",
                                color = palette.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (onOpenBriefingSettings != null) {
                        OutlinedButton(
                            onClick = onOpenBriefingSettings,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                backgroundColor = palette.tileSurface,
                                contentColor = palette.accent
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Briefing-Zeitplan${briefingTimeLabel?.let { " ($it Uhr)" } ?: ""}",
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "Planer",
                                color = palette.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Footer shortcut to CityImageSettingsActivity
                    OutlinedButton(
                        onClick = onOpenCityImageSettings,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = palette.tileSurface,
                            contentColor = palette.accent
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Stadt-Bilder verwalten",
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    if (showSportPicker && selectedSport != null && onSelectSport != null) {
        SportPickerDialog(
            selectedSport = selectedSport,
            onSelectSport = { sport ->
                onSelectSport(sport)
                showSportPicker = false
            },
            onDismiss = { showSportPicker = false }
        )
    }
}

@Composable
private fun SheetTabPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = FrogTheme.palette
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) palette.accent.copy(alpha = 0.22f) else palette.tileSurface)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) palette.accent else palette.textSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = if (selected) palette.textPrimary else palette.textSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FavoritesSection(
    favorites: List<FavoriteLocation>,
    activeFavoriteId: String?,
    currentLocationName: String?,
    currentCountry: String?,
    currentLat: Double?,
    currentLon: Double?,
    isCurrentAlreadySaved: Boolean,
    onSaveCurrentLocation: () -> Unit,
    onSelectFavorite: (FavoriteLocation) -> Unit,
    onRemoveFavorite: (String) -> Unit
) {
    val palette = FrogTheme.palette
    val canSaveCurrent = !currentLocationName.isNullOrBlank() && currentLat != null && currentLon != null

    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = onSaveCurrentLocation,
            enabled = canSaveCurrent && !isCurrentAlreadySaved,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = palette.accent,
                contentColor = palette.screenBackground,
                disabledBackgroundColor = palette.tileSurfaceElevated,
                disabledContentColor = palette.textSecondary
            )
        ) {
            Icon(
                imageVector = if (isCurrentAlreadySaved) Icons.Default.Check else Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            val citySuffix = currentLocationName?.let { name ->
                val countrySuffix = currentCountry?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
                " ($name$countrySuffix)"
            } ?: ""
            Text(
                text = when {
                    !canSaveCurrent -> "Kein aktiver Standort geladen"
                    isCurrentAlreadySaved -> "Bereits gespeichert$citySuffix"
                    else -> "Aktuellen Ort speichern$citySuffix"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.tileSurface)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Noch keine Favoriten gespeichert.\nSpeichere den aktuellen Ort oder tippe auf den Stern bei Suchvorschlägen.",
                    color = palette.textSecondary,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(favorites, key = { it.id }) { fav ->
                    val isActive = fav.id == activeFavoriteId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isActive) palette.tileSurfaceElevated else palette.tileSurface)
                            .border(
                                width = if (isActive) 1.5.dp else 1.dp,
                                color = if (isActive) palette.accent else palette.dividerColor,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectFavorite(fav) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = if (isActive) palette.accent else palette.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (fav.country.isNotBlank()) "${fav.name}, ${fav.country}" else fav.name,
                                    color = palette.textPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (isActive) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(palette.accent.copy(alpha = 0.22f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "Aktiv",
                                            color = palette.accent,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${"%.2f".format(fav.latitude)}°, ${"%.2f".format(fav.longitude)}°",
                                color = palette.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                        IconButton(onClick = { onRemoveFavorite(fav.id) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Favorit löschen",
                                tint = palette.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemesSection(
    selectedTheme: AppThemeId,
    onSelectTheme: (AppThemeId) -> Unit
) {
    val palette = FrogTheme.palette
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(AppThemeId.entries, key = { it.id }) { theme ->
            val themePalette = remember(theme) { paletteForTheme(theme) }
            val isSelected = theme == selectedTheme

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSelected) palette.tileSurfaceElevated else palette.tileSurface)
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) palette.accent else palette.dividerColor,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onSelectTheme(theme) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    themePalette.previewSwatches.forEach { swatch ->
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(1.dp, palette.textPrimary.copy(alpha = 0.35f), CircleShape)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = theme.displayName,
                            color = palette.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (theme.isPlaceholder) {
                                        palette.accentSecondary.copy(alpha = 0.18f)
                                    } else {
                                        palette.accent.copy(alpha = 0.22f)
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (theme.isPlaceholder) "Platzhalter" else "Standard",
                                color = if (theme.isPlaceholder) palette.accentSecondary else palette.accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = theme.description,
                        color = palette.textSecondary,
                        fontSize = 12.sp
                    )
                }
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Ausgewählt",
                        tint = palette.accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
