package com.frogweather.ui.analysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.frogweather.analysis.ConfidenceResult
import com.frogweather.analysis.ForecastConfidenceEngine
import com.frogweather.analysis.HistoricalNormalCalculator
import com.frogweather.analysis.HistoryDayPoint
import com.frogweather.analysis.HistoryGraphData
import com.frogweather.analysis.HourSkipEvaluation
import com.frogweather.analysis.HourlyNormalPoint
import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.NormalComparison
import com.frogweather.analysis.SportAdvisor
import com.frogweather.analysis.SportType
import com.frogweather.analysis.SportVerdict
import com.frogweather.analysis.SportWindow
import com.frogweather.analysis.UvLevel
import com.frogweather.data.TrackRecordSummary
import com.frogweather.network.OpenMeteoModelIds
import com.frogweather.ui.theme.FrogTheme
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class AnalysisUiState(
    val isLoadingForecast: Boolean = false,
    val isLoadingHistory: Boolean = false,
    val forecastError: String? = null,
    val historyError: String? = null,
    val currentTempC: Double? = null,
    val currentUvIndex: Double? = null,
    val peakUvIndex: Double? = null,
    val peakUvHourLabel: String? = null,
    val utcOffsetSeconds: Int = 0,
    val normalComparison: NormalComparison? = null,
    val dailyNormalMinC: Double? = null,
    val dailyNormalMaxC: Double? = null,
    val currentHourOfDay: Double? = null,
    val normalCurve24h: List<HourlyNormalPoint> = emptyList(),
    val history30Days: HistoryGraphData? = null,
    val hourlyPoints: List<HourlyWeatherPoint> = emptyList(),
    val sportVerdict: SportVerdict? = null,
    val bestSportWindow: SportWindow? = null,
    val confidence: ConfidenceResult? = null,
    val trackRecord: TrackRecordSummary? = null,
    val loadedLat: Double? = null,
    val loadedLon: Double? = null
)

@Composable
fun AnalysisScreen(
    cityName: String?,
    country: String?,
    latitude: Double?,
    longitude: Double?,
    uiState: AnalysisUiState,
    selectedSport: SportType,
    selectedHistoryDays: Int,
    onSelectSport: (SportType) -> Unit,
    onSelectHistoryDays: (Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = FrogTheme.palette
    val scrollState = rememberScrollState()
    var showSportPicker by remember { mutableStateOf(false) }

    val placeLabel = listOfNotNull(cityName, country?.takeIf { it.isNotBlank() })
        .joinToString(", ")
        .ifBlank { "Kein Standort gewählt" }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 0. Header & Status
        Surface(
            color = palette.tileSurface,
            shape = RoundedCornerShape(18.dp),
            elevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Wetter-Analyse",
                            color = palette.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val coordText = if (latitude != null && longitude != null) {
                            String.format(Locale.GERMAN, " (%.2f°, %.2f°)", latitude, longitude)
                        } else {
                            ""
                        }
                        Text(
                            text = "$placeLabel$coordText",
                            color = palette.accent,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onRetry, enabled = latitude != null && longitude != null) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Analyse aktualisieren",
                            tint = if (latitude != null && longitude != null) palette.accent else palette.textSecondary
                        )
                    }
                }

                if ((uiState.isLoadingForecast || uiState.isLoadingHistory) &&
                    uiState.hourlyPoints.isEmpty() &&
                    uiState.history30Days == null
                ) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = palette.accent,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Analysiere Multi-Modell- & Klimadaten...",
                            color = palette.textSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        if (latitude == null || longitude == null) {
            Surface(
                color = palette.tileSurface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Bitte zuerst einen Standort suchen oder GPS aktivieren, um Normalwerte, UV-Prognose, Sport-Check und Modell-Konfidenz anzuzeigen.",
                    color = palette.textSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
            TrackRecordCard(trackRecord = uiState.trackRecord)
            return@Column
        }

        val errorBannerText = listOfNotNull(uiState.forecastError, uiState.historyError)
            .distinct()
            .joinToString("\n")
        if (errorBannerText.isNotBlank()) {
            Surface(
                color = palette.tileSurfaceElevated,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFFFA726), RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFFA726),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = errorBannerText,
                            color = palette.textPrimary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = palette.accent,
                            contentColor = palette.screenBackground
                        )
                    ) {
                        Text(text = "Erneut versuchen", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }

        // 1. Historischer Normalwert & 7/14/30-Tage-Verlauf
        HistoricalNormalCard(
            normalComparison = uiState.normalComparison,
            curve24h = uiState.normalCurve24h,
            currentHourOfDay = uiState.currentHourOfDay,
            isLoading = uiState.isLoadingHistory
        )

        HistorySeriesCard(
            history30Days = uiState.history30Days,
            selectedDays = selectedHistoryDays,
            normalMeanC = uiState.normalComparison?.normalTempC,
            isLoading = uiState.isLoadingHistory,
            onSelectDays = onSelectHistoryDays
        )

        // 2. UV-Index & "Hours to Skip" mit Sportart-Wahl
        UvAndSportCard(
            currentUvIndex = uiState.currentUvIndex,
            peakUvIndex = uiState.peakUvIndex,
            peakUvHourLabel = uiState.peakUvHourLabel,
            selectedSport = selectedSport,
            sportVerdict = uiState.sportVerdict,
            bestSportWindow = uiState.bestSportWindow,
            utcOffsetSeconds = uiState.utcOffsetSeconds,
            isLoading = uiState.isLoadingForecast,
            onOpenSportPicker = { showSportPicker = true }
        )

        // 3. Vorhersage-Konfidenz (Multi-Modell)
        ConfidenceCard(
            confidence = uiState.confidence,
            isLoading = uiState.isLoadingForecast
        )

        // 4. Lokaler Track Record
        TrackRecordCard(trackRecord = uiState.trackRecord)

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showSportPicker) {
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
private fun HistoricalNormalCard(
    normalComparison: NormalComparison?,
    curve24h: List<HourlyNormalPoint>,
    currentHourOfDay: Double?,
    isLoading: Boolean
) {
    val palette = FrogTheme.palette
    Surface(
        color = palette.tileSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "HISTORISCHER NORMALWERT (5-JAHRES-BASIS)",
                color = palette.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            )

            if (normalComparison == null) {
                Text(
                    text = if (isLoading) {
                        "Berechne sinusförmige Tagesnormalwerte..."
                    } else {
                        "Noch keine historischen Klimadaten für diesen Standort geladen."
                    },
                    color = palette.textSecondary,
                    fontSize = 13.sp
                )
            } else {
                Text(
                    text = normalComparison.normalAtTimeLabel,
                    color = palette.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                val deltaColor = when {
                    abs(normalComparison.deltaC) < 0.5 -> Color(0xFF66BB6A)
                    normalComparison.deltaC > 0 -> Color(0xFFFFA726)
                    else -> Color(0xFF4FC3F7)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (normalComparison.currentTempC.isFinite()) {
                            String.format(Locale.GERMAN, "Aktuell: %.1f °C", normalComparison.currentTempC)
                        } else {
                            "Aktuell: --"
                        },
                        color = palette.textSecondary,
                        fontSize = 13.sp
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(deltaColor.copy(alpha = 0.20f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = normalComparison.deltaLabel,
                            color = deltaColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (curve24h.isNotEmpty()) {
                    SinusoidalCurveCanvas(
                        curve24h = curve24h,
                        currentHourOfDay = currentHourOfDay,
                        currentTempC = normalComparison.currentTempC.takeIf { it.isFinite() },
                        accentColor = palette.accent,
                        lineSecondaryColor = palette.textSecondary,
                        currentDotColor = deltaColor
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("00h", "06h", "12h", "18h", "24h").forEach { tick ->
                            Text(text = tick, color = palette.textSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SinusoidalCurveCanvas(
    curve24h: List<HourlyNormalPoint>,
    currentHourOfDay: Double?,
    currentTempC: Double?,
    accentColor: Color,
    lineSecondaryColor: Color,
    currentDotColor: Color
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
    ) {
        val allTemps = curve24h.map { it.tempC } + listOfNotNull(currentTempC)
        val minVal = allTemps.minOrNull() ?: 0.0
        val maxVal = allTemps.maxOrNull() ?: 1.0
        val span = (maxVal - minVal).coerceAtLeast(1.0)
        val padY = 8.dp.toPx()
        val usableH = (size.height - padY * 2f).coerceAtLeast(1f)

        fun yFor(temp: Double): Float {
            val frac = ((temp - minVal) / span).toFloat().coerceIn(0f, 1f)
            return size.height - padY - frac * usableH
        }

        val midY = yFor((minVal + maxVal) / 2.0)
        drawLine(
            color = lineSecondaryColor.copy(alpha = 0.25f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        )

        val path = Path()
        val count = curve24h.size
        curve24h.forEachIndexed { idx, pt ->
            val x = if (count <= 1) 0f else (idx.toFloat() / (count - 1).toFloat()) * size.width
            val y = yFor(pt.tempC)
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = accentColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        if (currentHourOfDay != null && currentHourOfDay.isFinite()) {
            val xFrac = (currentHourOfDay / 23.0).toFloat().coerceIn(0f, 1f)
            val cx = xFrac * size.width
            val normAtHour = curve24h.minByOrNull { abs(it.hour - currentHourOfDay) }?.tempC
            if (normAtHour != null) {
                val cy = yFor(normAtHour)
                drawCircle(
                    color = accentColor,
                    radius = 4.5.dp.toPx(),
                    center = Offset(cx, cy)
                )
            }
            if (currentTempC != null) {
                val curY = yFor(currentTempC)
                drawCircle(
                    color = currentDotColor.copy(alpha = 0.28f),
                    radius = 8.dp.toPx(),
                    center = Offset(cx, curY)
                )
                drawCircle(
                    color = currentDotColor,
                    radius = 4.5.dp.toPx(),
                    center = Offset(cx, curY)
                )
            }
        }
    }
}

@Composable
private fun HistorySeriesCard(
    history30Days: HistoryGraphData?,
    selectedDays: Int,
    normalMeanC: Double?,
    isLoading: Boolean,
    onSelectDays: (Int) -> Unit
) {
    val palette = FrogTheme.palette

    val sliced = remember(history30Days, selectedDays) {
        if (history30Days == null) {
            null
        } else {
            val pts = history30Days.points
            HistoricalNormalCalculator.buildHistorySeries(
                dailyDates = pts.map { it.date },
                mins = pts.map { it.minTempC },
                maxs = pts.map { it.maxTempC },
                means = pts.map { it.meanTempC },
                days = selectedDays
            )
        }
    }

    var selectedIndex by remember(sliced) {
        mutableStateOf(sliced?.points?.lastIndex?.takeIf { it >= 0 })
    }

    Surface(
        color = palette.tileSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TEMPERATURVERLAUF",
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7, 14, 30).forEach { days ->
                        val active = days == selectedDays
                        Box(
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) palette.accent else palette.tileSurfaceElevated)
                                .clickable { onSelectDays(days) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${days}T",
                                color = if (active) palette.screenBackground else palette.textSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            if (sliced == null || sliced.points.isEmpty()) {
                Text(
                    text = if (isLoading) {
                        "Lade historische Tageswerte über Open-Meteo Archive..."
                    } else {
                        "Keine historischen Tagesdaten verfügbar."
                    },
                    color = palette.textSecondary,
                    fontSize = 13.sp
                )
            } else {
                val points = sliced.points
                HistoryCanvasChart(
                    points = points,
                    selectedIndex = selectedIndex,
                    normalBaselineC = normalMeanC,
                    accentColor = palette.accent,
                    gridColor = palette.dividerColor,
                    baselineColor = palette.textSecondary,
                    onSelectIndex = { selectedIndex = it }
                )

                val selectedPoint = selectedIndex?.let { points.getOrNull(it) }
                Surface(
                    color = palette.tileSurfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (selectedPoint != null) {
                            String.format(
                                Locale.GERMAN,
                                "%s: Ø %.1f °C • Min %.1f °C / Max %.1f °C",
                                selectedPoint.date,
                                selectedPoint.meanTempC,
                                selectedPoint.minTempC,
                                selectedPoint.maxTempC
                            )
                        } else {
                            "Tippen oder wischen für Tageswerte"
                        },
                        color = palette.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = sliced.periodMeanC?.let {
                            String.format(Locale.GERMAN, "Ø %.1f °C", it)
                        } ?: "Ø --",
                        color = palette.textSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = sliced.periodMinC?.let {
                            String.format(Locale.GERMAN, "Tief %.1f °C", it)
                        } ?: "Tief --",
                        color = palette.textSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = sliced.periodMaxC?.let {
                            String.format(Locale.GERMAN, "Hoch %.1f °C", it)
                        } ?: "Hoch --",
                        color = palette.textSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCanvasChart(
    points: List<HistoryDayPoint>,
    selectedIndex: Int?,
    normalBaselineC: Double?,
    accentColor: Color,
    gridColor: Color,
    baselineColor: Color,
    onSelectIndex: (Int) -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .pointerInput(points) {
                detectTapGestures { offset ->
                    if (points.isNotEmpty() && size.width > 0) {
                        val idx = ((offset.x / size.width.toFloat()) * (points.size - 1))
                            .roundToInt()
                            .coerceIn(0, points.lastIndex)
                        onSelectIndex(idx)
                    }
                }
            }
            .pointerInput(points) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    if (points.isNotEmpty() && size.width > 0) {
                        val idx = ((change.position.x / size.width.toFloat()) * (points.size - 1))
                            .roundToInt()
                            .coerceIn(0, points.lastIndex)
                        onSelectIndex(idx)
                    }
                }
            }
    ) {
        if (points.isEmpty()) return@Canvas

        val allValues = points.flatMap { listOf(it.minTempC, it.maxTempC) } +
            listOfNotNull(normalBaselineC?.takeIf { it.isFinite() })
        val minVal = allValues.minOrNull() ?: 0.0
        val maxVal = allValues.maxOrNull() ?: 1.0
        val span = (maxVal - minVal).coerceAtLeast(1.0)

        val padY = 12.dp.toPx()
        val usableH = (size.height - padY * 2f).coerceAtLeast(1f)

        fun yFor(temp: Double): Float {
            val frac = ((temp - minVal) / span).toFloat().coerceIn(0f, 1f)
            return size.height - padY - frac * usableH
        }

        fun xFor(index: Int): Float =
            if (points.size <= 1) size.width / 2f else (index.toFloat() / (points.size - 1).toFloat()) * size.width

        // Horizontal grid lines
        for (step in 0..2) {
            val gy = padY + usableH * (step / 2f)
            drawLine(
                color = gridColor,
                start = Offset(0f, gy),
                end = Offset(size.width, gy),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Optional normal baseline
        if (normalBaselineC != null && normalBaselineC.isFinite()) {
            val ny = yFor(normalBaselineC)
            drawLine(
                color = baselineColor.copy(alpha = 0.55f),
                start = Offset(0f, ny),
                end = Offset(size.width, ny),
                strokeWidth = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )
        }

        // Shaded min-max polygon
        if (points.size >= 2) {
            val bandPath = Path().apply {
                points.forEachIndexed { idx, pt ->
                    val x = xFor(idx)
                    val y = yFor(pt.maxTempC)
                    if (idx == 0) moveTo(x, y) else lineTo(x, y)
                }
                for (idx in points.indices.reversed()) {
                    lineTo(xFor(idx), yFor(points[idx].minTempC))
                }
                close()
            }
            drawPath(path = bandPath, color = accentColor.copy(alpha = 0.20f))
        }

        // Mean temperature polyline
        val meanPath = Path()
        points.forEachIndexed { idx, pt ->
            val x = xFor(idx)
            val y = yFor(pt.meanTempC)
            if (idx == 0) meanPath.moveTo(x, y) else meanPath.lineTo(x, y)
        }
        drawPath(
            path = meanPath,
            color = accentColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // Selected crosshair & dot
        val sel = selectedIndex?.takeIf { it in points.indices }
        if (sel != null) {
            val sx = xFor(sel)
            val sy = yFor(points[sel].meanTempC)
            drawLine(
                color = accentColor.copy(alpha = 0.6f),
                start = Offset(sx, 0f),
                end = Offset(sx, size.height),
                strokeWidth = 1.2.dp.toPx()
            )
            drawCircle(
                color = accentColor.copy(alpha = 0.25f),
                radius = 9.dp.toPx(),
                center = Offset(sx, sy)
            )
            drawCircle(
                color = accentColor,
                radius = 4.5.dp.toPx(),
                center = Offset(sx, sy)
            )
        }
    }
}

@Composable
private fun UvAndSportCard(
    currentUvIndex: Double?,
    peakUvIndex: Double?,
    peakUvHourLabel: String?,
    selectedSport: SportType,
    sportVerdict: SportVerdict?,
    bestSportWindow: SportWindow?,
    utcOffsetSeconds: Int,
    isLoading: Boolean,
    onOpenSportPicker: () -> Unit
) {
    val palette = FrogTheme.palette
    var showAllHours by remember { mutableStateOf(false) }

    Surface(
        color = palette.tileSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "UV-INDEX & SPORT-CHECK",
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedButton(
                    onClick = onOpenSportPicker,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, palette.dividerColor),
                    colors = ButtonDefaults.outlinedButtonColors(
                        backgroundColor = palette.tileSurfaceElevated,
                        contentColor = palette.accent
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Sportart wählen: ${selectedSport.displayName}",
                        color = palette.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // UV Summary Strip
            val uvVal = currentUvIndex ?: sportVerdict?.peakUvIndex
            val uvLevel = uvVal?.let { SportAdvisor.classifyUv(it) }
            val uvColor = uvLevelColor(uvLevel, palette.textSecondary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(uvColor.copy(alpha = 0.20f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.WbSunny,
                            contentDescription = null,
                            tint = uvColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (uvVal != null && uvLevel != null) {
                                String.format(Locale.GERMAN, "UV %.1f · %s", uvVal, uvLevel.label)
                            } else {
                                "UV --"
                            },
                            color = uvColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (peakUvIndex != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.tileSurfaceElevated)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = String.format(
                                Locale.GERMAN,
                                "Tagespeak: %.1f%s",
                                peakUvIndex,
                                peakUvHourLabel?.let { " ($it)" } ?: ""
                            ),
                            color = palette.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (uvVal != null) {
                Text(
                    text = SportAdvisor.uvAdvice(uvVal),
                    color = palette.textSecondary,
                    fontSize = 12.sp
                )
            }

            if (sportVerdict == null) {
                Text(
                    text = if (isLoading) {
                        "Berechne „Hours to Skip“ für ${selectedSport.displayName}..."
                    } else {
                        "Keine stündlichen Prognosedaten für die Sportbewertung verfügbar."
                    },
                    color = palette.textSecondary,
                    fontSize = 13.sp
                )
            } else {
                // Best Sport Window
                Surface(
                    color = palette.tileSurfaceElevated,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (bestSportWindow != null) {
                            val offset = runCatching { ZoneOffset.ofTotalSeconds(utcOffsetSeconds) }
                                .getOrDefault(ZoneOffset.UTC)
                            val todayDate = Instant.now().atOffset(offset).toLocalDate()
                            val startDate = Instant.ofEpochMilli(bestSportWindow.startEpochMillis)
                                .atOffset(offset)
                                .toLocalDate()
                            val endDate = Instant.ofEpochMilli(bestSportWindow.endEpochMillis)
                                .atOffset(offset)
                                .toLocalDate()
                            val spansOtherDay = startDate != todayDate || endDate != startDate
                            val startStr = if (spansOtherDay) {
                                formatDayHour(bestSportWindow.startEpochMillis, utcOffsetSeconds)
                            } else {
                                formatHour(bestSportWindow.startEpochMillis, utcOffsetSeconds)
                            }
                            val endStr = if (spansOtherDay || endDate != startDate) {
                                formatDayHour(bestSportWindow.endEpochMillis, utcOffsetSeconds)
                            } else {
                                formatHour(bestSportWindow.endEpochMillis, utcOffsetSeconds)
                            }
                            Text(
                                text = "Bestes Fenster (${selectedSport.displayName}): $startStr – $endStr",
                                color = palette.accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = bestSportWindow.summaryLabel,
                                color = palette.textPrimary,
                                fontSize = 12.sp
                            )
                        } else {
                            Text(
                                text = "Im Prognosezeitraum kein geeignetes Fenster für ${selectedSport.displayName} gefunden.",
                                color = Color(0xFFFFA726),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Hours to Skip summary & filter
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusColor = if (sportVerdict.skipHoursCount == 0) {
                        Color(0xFF66BB6A)
                    } else {
                        Color(0xFFFFA726)
                    }
                    Text(
                        text = sportVerdict.summaryText,
                        color = statusColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChipPill(
                            label = "Nur kritische",
                            selected = !showAllHours,
                            onClick = { showAllHours = false }
                        )
                        FilterChipPill(
                            label = "Alle 24h",
                            selected = showAllHours,
                            onClick = { showAllHours = true }
                        )
                    }
                }

                val rowsToShow: List<HourSkipEvaluation> = if (showAllHours) {
                    sportVerdict.allEvaluations.take(24)
                } else {
                    sportVerdict.hoursToSkip.take(12)
                }

                if (rowsToShow.isEmpty() && !showAllHours) {
                    Text(
                        text = "Keine Stunden müssen gemieden werden – freie Bahn für ${selectedSport.displayName}!",
                        color = palette.textSecondary,
                        fontSize = 12.sp
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowsToShow.forEach { eval ->
                            HourEvaluationRow(
                                eval = eval,
                                utcOffsetSeconds = utcOffsetSeconds
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = FrogTheme.palette
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) palette.accent else palette.tileSurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) palette.screenBackground else palette.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HourEvaluationRow(
    eval: HourSkipEvaluation,
    utcOffsetSeconds: Int
) {
    val palette = FrogTheme.palette
    val timeLabel = formatDayHour(eval.hour.epochMillis, utcOffsetSeconds)
    val badgeColor = if (eval.shouldSkip) Color(0xFFFF8A80) else Color(0xFF66BB6A)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.tileSurfaceElevated)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(badgeColor)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = timeLabel,
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = String.format(
                        Locale.GERMAN,
                        "%.0f °C • UV %.1f • %.0f km/h",
                        eval.hour.tempC,
                        eval.hour.uvIndex,
                        eval.hour.windKmh
                    ),
                    color = palette.textSecondary,
                    fontSize = 11.sp
                )
            }
            Text(
                text = if (eval.shouldSkip) {
                    "Meiden: ${eval.reasonLabels.joinToString(" • ")}"
                } else {
                    "Geeignet (Score ${eval.suitabilityScore}/100)"
                },
                color = if (eval.shouldSkip) Color(0xFFFFB74D) else Color(0xFF81C784),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ConfidenceCard(
    confidence: ConfidenceResult?,
    isLoading: Boolean
) {
    val palette = FrogTheme.palette
    Surface(
        color = palette.tileSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "VORHERSAGE-KONFIDENZ",
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
                if (confidence != null) {
                    val tierColor = when (confidence.tierLabel) {
                        "Hoch" -> Color(0xFF66BB6A)
                        "Mittel" -> Color(0xFFFFA726)
                        else -> Color(0xFFFF8A80)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(tierColor.copy(alpha = 0.22f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = confidence.formattedLabel,
                            color = tierColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (confidence == null) {
                Text(
                    text = if (isLoading) {
                        "Vergleiche ECMWF, GFS, ICON-D2 und OpenWeatherMap..."
                    } else {
                        "Noch keine Multi-Modell-Daten geladen."
                    },
                    color = palette.textSecondary,
                    fontSize = 13.sp
                )
            } else {
                val expectedModels = listOf(
                    OpenMeteoModelIds.ECMWF,
                    OpenMeteoModelIds.GFS,
                    OpenMeteoModelIds.ICON_D2,
                    OpenMeteoModelIds.OWM
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    expectedModels.chunked(2).forEach { rowModels ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowModels.forEach { modelId ->
                                val isActive = modelId in confidence.activeModels
                                val isExcluded = modelId in confidence.excludedModels
                                val label = when {
                                    isActive -> "✓ ${ForecastConfidenceEngine.modelDisplayName(modelId)}"
                                    isExcluded && modelId == OpenMeteoModelIds.ICON_D2 -> "ICON-D2 (außerhalb)"
                                    isExcluded -> "${ForecastConfidenceEngine.modelDisplayName(modelId)} (ohne Daten)"
                                    else -> "${ForecastConfidenceEngine.modelDisplayName(modelId)} (fehlt)"
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.tileSurfaceElevated)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isActive) palette.accent else palette.textSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = confidence.divergenceExplanation,
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

@Composable
private fun TrackRecordCard(trackRecord: TrackRecordSummary?) {
    val palette = FrogTheme.palette
    Surface(
        color = palette.tileSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "LOKALER TRACK RECORD",
                color = palette.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            )

            if (trackRecord == null || trackRecord.verifiedCount == 0) {
                val pending = trackRecord?.pendingCount ?: 0
                Text(
                    text = if (pending > 0) {
                        "$pending Prognose-Snapshot(s) gespeichert – automatischer Abgleich erfolgt nach Ablauf des Vorhersage-Zeitraums."
                    } else {
                        "Noch keine Verlaufsdaten vorhanden. Mit jedem Wetterabruf speichert FrogWeather Prognosen lokal und prüft sie später automatisch gegen das tatsächliche Wetter."
                    },
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TrackMetricTile(
                        label = "Ø Temp.-Abweichung",
                        value = trackRecord.meanTempErrorC?.let {
                            String.format(Locale.GERMAN, "±%.1f °C", it)
                        } ?: "--",
                        modifier = Modifier.weight(1f)
                    )
                    TrackMetricTile(
                        label = "Regen-Trefferquote",
                        value = trackRecord.rainHitRatePct?.let { "$it %" } ?: "--",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TrackMetricTile(
                        label = "Trockene Sportfenster",
                        value = trackRecord.drySportWindowAccuracyPct?.let { "$it %" } ?: "--",
                        modifier = Modifier.weight(1f)
                    )
                    TrackMetricTile(
                        label = "Bestes Modell",
                        value = trackRecord.bestModel?.let { name ->
                            val mae = trackRecord.bestModelMaeC
                            if (mae != null) {
                                String.format(Locale.GERMAN, "%s (±%.1f°)", name, mae)
                            } else {
                                name
                            }
                        } ?: "${trackRecord.verifiedCount} Checks",
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "${trackRecord.verifiedCount} verifizierte Prognosen • ${trackRecord.pendingCount} ausstehend",
                    color = palette.textSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun TrackMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val palette = FrogTheme.palette
    Surface(
        color = palette.tileSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                color = palette.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                color = palette.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SportPickerDialog(
    selectedSport: SportType,
    onSelectSport: (SportType) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = FrogTheme.palette
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = palette.screenBackground,
            shape = RoundedCornerShape(20.dp),
            elevation = 10.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sportart wählen",
                        color = palette.textPrimary,
                        fontSize = 18.sp,
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

                SportType.entries.forEach { sport ->
                    val isSelected = sport == selectedSport
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) palette.tileSurfaceElevated else palette.tileSurface)
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) palette.accent else palette.dividerColor,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectSport(sport) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = sport.displayName,
                                color = palette.textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = SportAdvisor.sportDescription(sport),
                                color = palette.textSecondary,
                                fontSize = 11.sp
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Ausgewählt",
                                tint = palette.accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun uvLevelColor(level: UvLevel?, fallbackColor: Color): Color = when (level) {
    UvLevel.LOW -> Color(0xFF66BB6A)
    UvLevel.MODERATE -> Color(0xFFFFD54F)
    UvLevel.HIGH -> Color(0xFFFFA726)
    UvLevel.VERY_HIGH -> Color(0xFFFF8A80)
    UvLevel.EXTREME -> Color(0xFFCE93D8)
    null -> fallbackColor
}

private fun formatHour(epochMillis: Long, utcOffsetSeconds: Int): String {
    val offset = runCatching { ZoneOffset.ofTotalSeconds(utcOffsetSeconds) }.getOrDefault(ZoneOffset.UTC)
    return Instant.ofEpochMilli(epochMillis)
        .atOffset(offset)
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN))
}

private fun formatDayHour(epochMillis: Long, utcOffsetSeconds: Int): String {
    val offset = runCatching { ZoneOffset.ofTotalSeconds(utcOffsetSeconds) }.getOrDefault(ZoneOffset.UTC)
    return Instant.ofEpochMilli(epochMillis)
        .atOffset(offset)
        .format(DateTimeFormatter.ofPattern("E HH:mm", Locale.GERMAN))
}
