package com.frogweather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frogweather.network.WeatherDetails
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

private val TileBackground = Color(0xFF1C2733)
private val TileShape = RoundedCornerShape(16.dp)

fun dewPoint(tempC: Double, humidityPct: Int): Double {
    val h = humidityPct.toDouble()
    val t = tempC
    val lnH = ln(h / 100.0)
    val numerator = 243.04 * (lnH + 17.625 * t / (243.04 + t))
    val denominator = 17.625 - (lnH + 17.625 * t / (243.04 + t))
    return numerator / denominator
}

fun windDirection(deg: Int): String {
    val dirs = arrayOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")
    return dirs[((deg + 22.5) / 45.0).toInt() % 8]
}

@Composable
fun WeatherDetailsGrid(details: WeatherDetails) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "WETTERDETAILS",
            color = Color.White,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
        // Row 1: Niederschlag + Wind
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PrecipitationTile(details.rainMm, modifier = Modifier.weight(1f).aspectRatio(1f))
            WindTile(details.windSpeed, details.windDeg, modifier = Modifier.weight(1f).aspectRatio(1f))
        }
        // Row 2: Sonnenaufgang/-untergang + Luftdruck
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SunriseSunsetTile(details.sunrise, details.sunset, modifier = Modifier.weight(1f).aspectRatio(1f))
            PressureBadgeTile(details.pressure, modifier = Modifier.weight(1f).aspectRatio(1f))
        }
        // Row 3: Luftqualität + Sichtweite
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AirQualityTile(details.aqi, modifier = Modifier.weight(1f).aspectRatio(1f))
            VisibilityTile(details.visibilityM, modifier = Modifier.weight(1f).aspectRatio(1f))
        }
        // Row 4: Luftfeuchtigkeit + Taupunkt
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HumidityTile(details.humidity, details.temperature, modifier = Modifier.weight(1f).aspectRatio(1f))
            DewPointTile(details.temperature, details.humidity, modifier = Modifier.weight(1f).aspectRatio(1f))
        }
    }
}

// ── Shared tile container ─────────────────────────────────────────────────────

@Composable
private fun TileBase(
    label: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .background(TileBackground, shape = TileShape)
            .padding(14.dp),
        horizontalAlignment = horizontalAlignment
    ) {
        Text(
            text = label.uppercase(),
            color = Color.White,
            fontSize = 14.sp,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

// ── Tile: Niederschlag ───────────────────────────────────────────────────────

@Composable
fun PrecipitationTile(rainMm: Double?, modifier: Modifier = Modifier) {
    val mm = rainMm ?: 0.0
    val noRain = mm == 0.0
    val subText = if (noRain) "Kein Niederschlag erwartet" else "${"%.1f".format(mm)} mm Niederschlag erwartet"

    Box(
        modifier = modifier
            .background(Color(0xFF1A1A2E), shape = TileShape)
            .padding(14.dp)
    ) {
        // Header: icon + label top-left
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Text(text = "🌧", fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Niederschlag",
                color = Color.White,
                fontSize = 14.sp,
                letterSpacing = 1.sp
            )
        }

        // Centre: mm value
        Text(
            text = "${"%.1f".format(mm)} mm",
            color = Color.White,
            fontSize = 32.sp,
            modifier = Modifier.align(Alignment.Center)
        )

        // Bottom: description
        Text(
            text = subText,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

// ── Tile: Wind ───────────────────────────────────────────────────────────────

@Composable
fun WindTile(speedMs: Double?, deg: Int?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(TileBackground, shape = TileShape)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header: larger like precipitation mm display
        Text(
            text = "WIND",
            color = Color.White,
            fontSize = 16.sp,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(8.dp))
        
        val kmh = speedMs?.let { it * 3.6 }
        val dir = deg?.let { windDirection(it) } ?: "--"

        // Compass: smaller (0.75x weight instead of 1f)
        Canvas(
            modifier = Modifier
                .weight(0.75f)
                .fillMaxWidth(0.75f)
                .padding(4.dp)
        ) {
            val radius = size.minDimension / 2f
            val strokeW = (radius * 0.07f).coerceAtLeast(3f)
            val dotR   = (radius * 0.12f).coerceAtLeast(5f)
            val tipInset = radius * 0.12f

            // Outer ring
            drawCircle(
                color = Color.White.copy(alpha = 0.15f),
                radius = radius,
                style = Stroke(strokeW)
            )
            // Inner tick ring
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = radius * 0.55f,
                style = Stroke(strokeW * 0.5f)
            )
            if (deg != null) {
                val angleRad = Math.toRadians(deg.toDouble() - 90.0)
                val tipX = center.x + (radius - tipInset) * cos(angleRad).toFloat()
                val tipY = center.y + (radius - tipInset) * sin(angleRad).toFloat()
                // Tail (opposite side, shorter)
                val tailX = center.x - (radius * 0.35f) * cos(angleRad).toFloat()
                val tailY = center.y - (radius * 0.35f) * sin(angleRad).toFloat()
                drawLine(
                    color = Color(0xFF4FC3F7),
                    start = Offset(tailX, tailY),
                    end = Offset(tipX, tipY),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = Color(0xFF4FC3F7),
                    radius = dotR,
                    center = Offset(tipX, tipY)
                )
                // Centre dot
                drawCircle(
                    color = Color.White.copy(alpha = 0.35f),
                    radius = strokeW * 0.8f
                )
            }
        }

        Text(
            text = kmh?.let { "${"%.0f".format(it)} km/h" } ?: "--",
            color = Color.White,
            fontSize = 18.sp
        )
        Text(
            text = "Von $dir",
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

// ── Tile: Sonnenaufgang & -untergang ─────────────────────────────────────────

@Composable
fun SunriseSunsetTile(sunriseEpoch: Long?, sunsetEpoch: Long?, modifier: Modifier = Modifier) {
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    val zone = ZoneId.systemDefault()
    val rise = sunriseEpoch ?: 0L
    val set  = sunsetEpoch  ?: 0L
    val riseStr = if (rise > 0) Instant.ofEpochSecond(rise).atZone(zone).format(fmt) else "--"
    val setStr  = if (set  > 0) Instant.ofEpochSecond(set).atZone(zone).format(fmt)  else "--"

    // Progress fraction: where is the sun now between rise and set?
    val now = System.currentTimeMillis() / 1000L
    val totalSecs = (set - rise).coerceAtLeast(1)
    val fraction = ((now - rise).toFloat() / totalSecs).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .background(TileBackground, shape = TileShape)
            .padding(14.dp)
    ) {
        // Content column
        Column(modifier = Modifier.fillMaxSize()) {

            // Header: sun icon + title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("☀️", fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Sonnenaufgang und Sonnenuntergang",
                    color = Color.White,
                    fontSize = 14.sp,
                    letterSpacing = 0.8.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            // Arc canvas: smaller arc (weight 0.6 instead of 1f)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.6f)
            ) {
                val w = size.width
                val h = size.height

                // Horizontal sky gradient: dark left/right, lighter in centre
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF0D1B2A),
                            Color(0xFF1E3A5F),
                            Color(0xFF0D1B2A)
                        )
                    )
                )

                // Bright horizon band in the lower half
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFF2A4A6B).copy(alpha = 0.5f)
                        ),
                        startY = h * 0.5f,
                        endY = h
                    )
                )

                // Horizon line
                val horizonY = h * 0.72f
                drawLine(
                    color = Color.White.copy(alpha = 0.25f),
                    start = Offset(0f, horizonY),
                    end = Offset(w, horizonY),
                    strokeWidth = 1.dp.toPx()
                )

                // Sine arc path: from left horizon edge, peaks at centre top, back to right horizon edge
                // y = horizonY - amplitude * sin(π * t)  for t in [0, 1]
                val amplitude = horizonY * 0.85f
                val arcPath = Path()
                val steps = 80
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val x = w * t
                    val y = horizonY - amplitude * sin(Math.PI * t).toFloat()
                    if (i == 0) arcPath.moveTo(x, y) else arcPath.lineTo(x, y)
                }
                // Dashed-style: draw full arc dim, then progress arc bright
                drawPath(
                    arcPath,
                    color = Color.White.copy(alpha = 0.18f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                // Elapsed portion of arc (bright yellow)
                val progressPath = Path()
                val progressSteps = (steps * fraction).toInt().coerceAtLeast(0)
                for (i in 0..progressSteps) {
                    val t = i / steps.toFloat()
                    val x = w * t
                    val y = horizonY - amplitude * sin(Math.PI * t).toFloat()
                    if (i == 0) progressPath.moveTo(x, y) else progressPath.lineTo(x, y)
                }
                drawPath(
                    progressPath,
                    color = Color(0xFFFDD835).copy(alpha = 0.8f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                // Sun circle at current position on arc
                val sunT = fraction
                val sunX = w * sunT
                val sunY = horizonY - amplitude * sin(Math.PI * sunT).toFloat()
                // Glow
                drawCircle(
                    color = Color(0xFFFDD835).copy(alpha = 0.25f),
                    radius = 12.dp.toPx(),
                    center = Offset(sunX, sunY)
                )
                drawCircle(
                    color = Color(0xFFFDD835),
                    radius = 7.dp.toPx(),
                    center = Offset(sunX, sunY)
                )

                // Sunrise marker dot on horizon (left)
                drawCircle(
                    color = Color.White.copy(alpha = 0.4f),
                    radius = 3.dp.toPx(),
                    center = Offset(0f, horizonY)
                )
                // Sunset marker dot on horizon (right)
                drawCircle(
                    color = Color.White.copy(alpha = 0.4f),
                    radius = 3.dp.toPx(),
                    center = Offset(w, horizonY)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Time row: sunrise left, sunset right - LARGER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("↑", color = Color(0xFFFDD835), fontSize = 16.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(riseStr, color = Color.White, fontSize = 16.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(setStr, color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("↓", color = Color(0xFF90CAF9), fontSize = 16.sp)
                }
            }
        }
    }
}

// ── Tile: Luftdruck (badge) ───────────────────────────────────────────────────

@Composable
fun PressureBadgeTile(pressure: Int?, modifier: Modifier = Modifier) {
    TileBase("Luftdruck", modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val hpa = pressure ?: 0
        val color = when {
            hpa == 0   -> Color.Gray
            hpa < 980  -> Color(0xFFEF5350)
            hpa < 1000 -> Color(0xFFFFA726)
            hpa < 1020 -> Color(0xFF66BB6A)
            else       -> Color(0xFF42A5F5)
        }
        val desc = when {
            hpa == 0   -> "--"
            hpa < 980  -> "Tief"
            hpa < 1000 -> "Unter normal"
            hpa < 1020 -> "Normal"
            else       -> "Hoch"
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp), // Increased padding for more space around circle
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f * 0.85f // Smaller circle (85% of before)
                val strokeW = (radius * 0.10f).coerceAtLeast(4f)
                drawCircle(color = color.copy(alpha = 0.18f))
                drawCircle(color = color, radius = radius, style = Stroke(strokeW))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val fontSize = if (hpa >= 1000) 20.sp else 22.sp // Slightly smaller to fit
                Text(
                    text = if (hpa == 0) "--" else "$hpa",
                    color = Color.White,
                    fontSize = fontSize
                )
                Text("hPa", color = Color.White, fontSize = 12.sp)
            }
        }
        Text(text = desc, color = color, fontSize = 14.sp)
    }
}

// ── Tile: Luftqualität ───────────────────────────────────────────────────────

@Composable
fun AirQualityTile(aqi: Int?, modifier: Modifier = Modifier) {
    TileBase("Luftqualität", modifier) {
        val level = aqi ?: 0
        val (label, color) = when (level) {
            1    -> "Gut"           to Color(0xFF66BB6A)
            2    -> "Akzeptabel"    to Color(0xFFD4E157)
            3    -> "Mäßig"         to Color(0xFFFFA726)
            4    -> "Schlecht"      to Color(0xFFEF5350)
            5    -> "Sehr schlecht" to Color(0xFFAB47BC)
            else -> "Unbekannt"     to Color.Gray
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (level == 0) "--" else level.toString(),
                    color = Color.White,
                    fontSize = 80.sp
                )
                Text(text = label, color = color, fontSize = 14.sp)
            }
        }
        // 5-segment AQI color bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val segColors = listOf(
                Color(0xFF66BB6A),
                Color(0xFFD4E157),
                Color(0xFFFFA726),
                Color(0xFFEF5350),
                Color(0xFFAB47BC)
            )
            segColors.forEachIndexed { i, c ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            if (level > 0 && i < level) c else c.copy(alpha = 0.2f),
                            RoundedCornerShape(3.dp)
                        )
                )
            }
        }
    }
}

// ── Tile: Sichtweite ─────────────────────────────────────────────────────────

@Composable
fun VisibilityTile(visibilityM: Int?, modifier: Modifier = Modifier) {
    TileBase("Sichtweite", modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val km = visibilityM?.div(1000.0) ?: 0.0
        val fraction = (km / 10.0).coerceIn(0.0, 1.0).toFloat()

        // Draw a road with fog/haze based on visibility
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            val w = size.width
            val h = size.height
            val horizonY = h * 0.55f

            // Sky gradient
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1E3A5F),
                        Color(0xFF0D1B2A)
                    ),
                    startY = 0f,
                    endY = horizonY
                )
            )

            // Ground/road base
            drawRect(
                color = Color(0xFF2A2A2A),
                topLeft = Offset(0f, horizonY),
                size = Size(w, h - horizonY)
            )

            // Road perspective - converging to center
            val roadWidthBottom = w * 0.9f
            val roadWidthTop = w * 0.15f
            val centerX = w / 2f

            // Road surface gradient
            val roadPath = Path().apply {
                moveTo(centerX - roadWidthTop / 2, horizonY)
                lineTo(centerX + roadWidthTop / 2, horizonY)
                lineTo(centerX + roadWidthBottom / 2, h)
                lineTo(centerX - roadWidthBottom / 2, h)
                close()
            }

            // Road color gradient (darker near horizon, lighter near bottom)
            drawPath(
                roadPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF3A3A3A),
                        Color(0xFF555555)
                    ),
                    startY = horizonY,
                    endY = h
                )
            )

            // Road markings - dashed center line
            val dashCount = 6
            for (i in 0 until dashCount) {
                val t1 = i / dashCount.toFloat()
                val t2 = (i + 0.5f) / dashCount.toFloat()

                val y1 = horizonY + (h - horizonY) * t1 * t1 // quadratic for perspective
                val y2 = horizonY + (h - horizonY) * t2 * t2

                val widthAtY1 = roadWidthTop + (roadWidthBottom - roadWidthTop) * t1
                val widthAtY2 = roadWidthTop + (roadWidthBottom - roadWidthTop) * t2

                drawLine(
                    color = Color.White.copy(alpha = 0.6f),
                    start = Offset(centerX - widthAtY1 / 2 + widthAtY1 / 4, y1),
                    end = Offset(centerX - widthAtY2 / 2 + widthAtY2 / 4, y2),
                    strokeWidth = 3f
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.6f),
                    start = Offset(centerX + widthAtY1 / 2 - widthAtY1 / 4, y1),
                    end = Offset(centerX + widthAtY2 / 2 - widthAtY2 / 4, y2),
                    strokeWidth = 3f
                )
            }

            // Center dashed line
            for (i in 0 until dashCount) {
                val t1 = i / dashCount.toFloat()
                val t2 = (i + 0.5f) / dashCount.toFloat()

                val y1 = horizonY + (h - horizonY) * t1 * t1
                val y2 = horizonY + (h - horizonY) * t2 * t2

                drawLine(
                    color = Color(0xFFFFA726).copy(alpha = 0.8f),
                    start = Offset(centerX, y1),
                    end = Offset(centerX, y2),
                    strokeWidth = 2f
                )
            }

            // Fog/haze overlay based on visibility - thicker fog for low visibility
            // The fog gradient covers the road and fades based on visibility fraction
            val fogAlpha = (0.85f - fraction * 0.7f).coerceIn(0.15f, 0.85f)

            // Fog gradient - thicker at horizon (far away), clearer at bottom (near)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.LightGray.copy(alpha = fogAlpha),
                        Color.LightGray.copy(alpha = fogAlpha * 0.6f),
                        Color.Transparent
                    ),
                    startY = horizonY * 0.5f,
                    endY = h
                )
            )

            // Additional horizontal fog band that represents visibility distance
            val visibilityLineY = horizonY + (h - horizonY) * (1 - fraction) * (1 - fraction)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0f),
                        Color.White.copy(alpha = fogAlpha * 0.5f),
                        Color.White.copy(alpha = fogAlpha * 0.8f),
                        Color.White.copy(alpha = fogAlpha * 0.5f),
                        Color.White.copy(alpha = 0f)
                    ),
                    startY = visibilityLineY - h * 0.15f,
                    endY = visibilityLineY + h * 0.15f
                )
            )
        }
        Text(
            text = "${"%.1f".format(km)} km",
            color = Color.White,
            fontSize = 18.sp
        )
        Text(
            text = when {
                km >= 10 -> "Sehr gut"
                km >= 5  -> "Gut"
                else     -> "Eingeschränkt"
            },
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

// ── Tile: Luftfeuchtigkeit — Wasserfüllstand ─────────────────────────────────

@Composable
fun HumidityTile(humidity: Int?, tempC: Double?, modifier: Modifier = Modifier) {
    val h = (humidity ?: 0).coerceIn(0, 100)
    val dp = if (tempC != null && humidity != null && humidity > 0) dewPoint(tempC, humidity) else null
    val fillFraction = h / 100f

    Box(
        modifier = modifier
            .background(TileBackground, shape = TileShape)
    ) {
        // Water fill canvas — drawn behind everything, covers full tile
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val canvasH = size.height
            val fillY = canvasH * (1f - fillFraction)
            val waveH = 14.dp.toPx()  // amplitude stays moderate
            val periods = 5            // 5 full sine waves across the width
            val segW = w / periods     // width of one period

            // Clip to rounded-rect shape of tile
            clipRect(left = 0f, top = fillY - waveH, right = w, bottom = canvasH) {
                // Solid water body below wave
                drawRect(
                    color = Color(0xFF1565C0).copy(alpha = 0.55f),
                    topLeft = Offset(0f, fillY),
                    size = Size(w, canvasH - fillY)
                )
                // Wave surface — `periods` full sine cycles via cubic beziers
                val wavePath = Path().apply {
                    moveTo(0f, fillY)
                    repeat(periods) { p ->
                        val x0 = segW * p
                        // First half-period: crest (upward)
                        cubicTo(
                            x0 + segW * 0.25f, fillY - waveH,
                            x0 + segW * 0.25f, fillY - waveH,
                            x0 + segW * 0.5f,  fillY
                        )
                        // Second half-period: trough (downward)
                        cubicTo(
                            x0 + segW * 0.75f, fillY + waveH,
                            x0 + segW * 0.75f, fillY + waveH,
                            x0 + segW,         fillY
                        )
                    }
                    lineTo(w, canvasH)
                    lineTo(0f, canvasH)
                    close()
                }
                drawPath(
                    wavePath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF42A5F5).copy(alpha = 0.85f),
                            Color(0xFF1565C0).copy(alpha = 0.65f)
                        ),
                        startY = fillY - waveH,
                        endY = canvasH
                    )
                )
            }
        }

        // Foreground content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            // Header: droplet + label - LARGER
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💧", fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Luftfeuchtigkeit",
                    color = Color.White,
                    fontSize = 15.sp,
                    letterSpacing = 1.sp
                )
            }

            // Centred percentage — fills remaining space
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$h%",
                    color = Color.White,
                    fontSize = 48.sp
                )
            }

            // Dew point badge bottom-left - LARGER
            dp?.let { dpVal ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(
                                Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "${dpVal.toInt()}°",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Taupunkt",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

// ── Tile: Taupunkt ───────────────────────────────────────────────────────────

@Composable
fun DewPointTile(tempC: Double?, humidity: Int?, modifier: Modifier = Modifier) {
    TileBase("Taupunkt", modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val dp = if (tempC != null && humidity != null && humidity > 0) dewPoint(tempC, humidity) else null
        val dpColor = when {
            dp == null -> Color.Gray
            dp < 10    -> Color(0xFF4FC3F7)
            dp < 16    -> Color(0xFF66BB6A)
            dp < 21    -> Color(0xFFFFA726)
            else       -> Color(0xFFEF5350)
        }
        val dpLabel = when {
            dp == null -> "--"
            dp < 10    -> "Trocken"
            dp < 16    -> "Angenehm"
            dp < 21    -> "Feucht"
            else       -> "Drückend"
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(20.dp), // Larger padding for smaller circle with more air
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f * 0.75f // Smaller circle (75% of before)
                val strokeW = (radius * 0.10f).coerceAtLeast(4f)
                drawCircle(color = dpColor.copy(alpha = 0.18f))
                drawCircle(color = dpColor, radius = radius, style = Stroke(strokeW))
            }
            Text(
                text = dp?.let { "${it.toInt()}°" } ?: "--",
                color = Color.White,
                fontSize = 28.sp // Slightly smaller to fit
            )
        }
        // Larger label below
        Text(text = dpLabel, color = dpColor, fontSize = 16.sp)
    }
}
