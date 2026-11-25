package com.frogweather.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.frogweather.WeatherType
import com.frogweather.data.WeatherScenario
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class LiveEffectMode {
    SUNNY_GLOW,
    CLEAR_NIGHT,
    CLOUDY,
    FOG,
    LIGHT_RAIN,
    RAIN,
    HEAVY_RAIN,
    THUNDERSTORM,
    SNOW,
    BLIZZARD,
    NONE
}

fun resolveLiveEffectMode(
    weatherType: WeatherType?,
    scenario: WeatherScenario?,
    isNight: Boolean
): LiveEffectMode {
    if (scenario != null) {
        return when (scenario) {
            WeatherScenario.SUNNY -> if (isNight) LiveEffectMode.CLEAR_NIGHT else LiveEffectMode.SUNNY_GLOW
            WeatherScenario.NIGHT_CLEAR -> LiveEffectMode.CLEAR_NIGHT
            WeatherScenario.PARTLY_CLOUDY,
            WeatherScenario.CLOUDY,
            WeatherScenario.NIGHT_CLOUDY -> LiveEffectMode.CLOUDY
            WeatherScenario.FOG -> LiveEffectMode.FOG
            WeatherScenario.LIGHT_RAIN -> LiveEffectMode.LIGHT_RAIN
            WeatherScenario.RAIN -> LiveEffectMode.RAIN
            WeatherScenario.HEAVY_RAIN -> LiveEffectMode.HEAVY_RAIN
            WeatherScenario.THUNDERSTORM -> LiveEffectMode.THUNDERSTORM
            WeatherScenario.LIGHT_SNOW,
            WeatherScenario.SNOW -> LiveEffectMode.SNOW
            WeatherScenario.BLIZZARD -> LiveEffectMode.BLIZZARD
        }
    }
    return when (weatherType) {
        WeatherType.SUNNY -> if (isNight) LiveEffectMode.CLEAR_NIGHT else LiveEffectMode.SUNNY_GLOW
        WeatherType.CLOUDY -> LiveEffectMode.CLOUDY
        WeatherType.RAINY -> LiveEffectMode.RAIN
        WeatherType.THUNDERSTORM -> LiveEffectMode.THUNDERSTORM
        WeatherType.SNOW -> LiveEffectMode.SNOW
        WeatherType.OTHER, null -> if (isNight) LiveEffectMode.CLEAR_NIGHT else LiveEffectMode.NONE
    }
}

private data class RainDropParticle(
    val xNorm: Float,
    val yNorm: Float,
    val lengthDp: Float,
    val speedPerSec: Float,
    val alpha: Float,
    val strokeDp: Float
)

private data class SnowFlakeParticle(
    val xNorm: Float,
    val yNorm: Float,
    val radiusDp: Float,
    val speedPerSec: Float,
    val driftAmp: Float,
    val driftFreq: Float,
    val phase: Float,
    val alpha: Float
)

private data class FogBandParticle(
    val yNorm: Float,
    val heightFrac: Float,
    val speedPerSec: Float,
    val phase: Float,
    val alpha: Float
)

private data class CloudWispParticle(
    val xNorm: Float,
    val yNorm: Float,
    val widthFrac: Float,
    val heightFrac: Float,
    val speedPerSec: Float,
    val alpha: Float
)

private data class StarParticle(
    val xNorm: Float,
    val yNorm: Float,
    val radiusDp: Float,
    val twinkleSpeed: Float,
    val phase: Float
)

private data class OverlayParticles(
    val rainDrops: List<RainDropParticle>,
    val snowFlakes: List<SnowFlakeParticle>,
    val fogBands: List<FogBandParticle>,
    val cloudWisps: List<CloudWispParticle>,
    val stars: List<StarParticle>
)

private fun buildParticlesForMode(mode: LiveEffectMode): OverlayParticles {
    val rng = Random(20261119)

    val rainCount = when (mode) {
        LiveEffectMode.LIGHT_RAIN -> 45
        LiveEffectMode.RAIN -> 75
        LiveEffectMode.HEAVY_RAIN, LiveEffectMode.THUNDERSTORM -> 105
        else -> 0
    }
    val rainDrops = List(rainCount) {
        val heavy = mode == LiveEffectMode.HEAVY_RAIN || mode == LiveEffectMode.THUNDERSTORM
        RainDropParticle(
            xNorm = rng.nextFloat(),
            yNorm = rng.nextFloat(),
            lengthDp = if (heavy) 16f + rng.nextFloat() * 14f else 11f + rng.nextFloat() * 10f,
            speedPerSec = if (heavy) 1.05f + rng.nextFloat() * 0.55f else 0.72f + rng.nextFloat() * 0.4f,
            alpha = (0.16f + rng.nextFloat() * 0.22f).coerceAtMost(0.40f),
            strokeDp = if (heavy) 1.6f else 1.2f
        )
    }

    val snowCount = when (mode) {
        LiveEffectMode.SNOW -> 55
        LiveEffectMode.BLIZZARD -> 85
        else -> 0
    }
    val snowFlakes = List(snowCount) {
        val blizzard = mode == LiveEffectMode.BLIZZARD
        SnowFlakeParticle(
            xNorm = rng.nextFloat(),
            yNorm = rng.nextFloat(),
            radiusDp = 1.8f + rng.nextFloat() * 2.8f,
            speedPerSec = if (blizzard) 0.28f + rng.nextFloat() * 0.22f else 0.11f + rng.nextFloat() * 0.10f,
            driftAmp = if (blizzard) 0.06f + rng.nextFloat() * 0.05f else 0.02f + rng.nextFloat() * 0.025f,
            driftFreq = 0.8f + rng.nextFloat() * 1.4f,
            phase = rng.nextFloat() * (2f * PI.toFloat()),
            alpha = (0.30f + rng.nextFloat() * 0.36f).coerceAtMost(0.70f)
        )
    }

    val fogBands = if (mode == LiveEffectMode.FOG) {
        List(5) { idx ->
            FogBandParticle(
                yNorm = 0.14f + idx * 0.17f,
                heightFrac = 0.16f + rng.nextFloat() * 0.07f,
                speedPerSec = 0.02f + rng.nextFloat() * 0.03f,
                phase = rng.nextFloat() * (2f * PI.toFloat()),
                alpha = (0.10f + rng.nextFloat() * 0.09f).coerceAtMost(0.20f)
            )
        }
    } else {
        emptyList()
    }

    val cloudWisps = if (mode == LiveEffectMode.CLOUDY || mode == LiveEffectMode.THUNDERSTORM) {
        List(6) { idx ->
            CloudWispParticle(
                xNorm = rng.nextFloat(),
                yNorm = 0.05f + idx * 0.065f,
                widthFrac = 0.38f + rng.nextFloat() * 0.28f,
                heightFrac = 0.09f + rng.nextFloat() * 0.05f,
                speedPerSec = 0.012f + rng.nextFloat() * 0.018f,
                alpha = if (mode == LiveEffectMode.THUNDERSTORM) 0.14f else 0.10f
            )
        }
    } else {
        emptyList()
    }

    val stars = if (mode == LiveEffectMode.CLEAR_NIGHT) {
        List(38) {
            StarParticle(
                xNorm = rng.nextFloat(),
                yNorm = rng.nextFloat() * 0.55f,
                radiusDp = 1.0f + rng.nextFloat() * 1.6f,
                twinkleSpeed = 1.0f + rng.nextFloat() * 2.2f,
                phase = rng.nextFloat() * (2f * PI.toFloat())
            )
        }
    } else {
        emptyList()
    }

    return OverlayParticles(
        rainDrops = rainDrops,
        snowFlakes = snowFlakes,
        fogBands = fogBands,
        cloudWisps = cloudWisps,
        stars = stars
    )
}

@Composable
fun LiveWeatherOverlay(
    weatherType: WeatherType?,
    scenario: WeatherScenario?,
    isNight: Boolean,
    windSpeedMs: Double?,
    isDimmed: Boolean = false,
    modifier: Modifier = Modifier
) {
    val effectMode = remember(weatherType, scenario, isNight) {
        resolveLiveEffectMode(weatherType, scenario, isNight)
    }

    val intensityMultiplier by animateFloatAsState(
        targetValue = if (isDimmed) 0.15f else 1f,
        animationSpec = tween(durationMillis = 350),
        label = "overlayIntensity"
    )

    if (effectMode == LiveEffectMode.NONE) return

    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> isResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val particles = remember(effectMode) { buildParticlesForMode(effectMode) }
    val animTimeSec = remember(effectMode) { mutableFloatStateOf(0f) }

    LaunchedEffect(effectMode, isResumed) {
        if (!isResumed) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val dt = ((nowNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNanos = nowNanos
            animTimeSec.floatValue += dt
        }
    }

    val windLeanFactor = ((windSpeedMs?.takeIf { it.isFinite() } ?: 2.0).toFloat() / 12f).coerceIn(0.05f, 0.45f)
    val boltPath = remember { Path() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
    ) {
        val t = animTimeSec.floatValue
        val w = size.width
        val h = size.height
        val scaleAlpha = intensityMultiplier

        when (effectMode) {
            LiveEffectMode.SUNNY_GLOW -> {
                val pulse = 0.85f + 0.15f * sin(t * 0.9f)
                val sunCenter = Offset(w * 0.86f, h * 0.12f)
                val sunRadius = w.coerceAtLeast(h) * 0.55f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFE082).copy(alpha = (0.24f * pulse * scaleAlpha).coerceAtMost(0.24f)),
                            Color(0xFFFFCA28).copy(alpha = (0.10f * pulse * scaleAlpha).coerceAtMost(0.24f)),
                            Color.Transparent
                        ),
                        center = sunCenter,
                        radius = sunRadius
                    ),
                    radius = sunRadius,
                    center = sunCenter
                )
            }

            LiveEffectMode.CLEAR_NIGHT -> {
                particles.stars.forEach { star ->
                    val twinkle = 0.35f + 0.65f * ((sin(t * star.twinkleSpeed + star.phase) + 1f) * 0.5f)
                    drawCircle(
                        color = Color(0xFFE3F2FD).copy(alpha = (0.55f * twinkle * scaleAlpha).coerceAtMost(0.60f)),
                        radius = star.radiusDp.dp.toPx(),
                        center = Offset(star.xNorm * w, star.yNorm * h)
                    )
                }
            }

            LiveEffectMode.CLOUDY -> {
                particles.cloudWisps.forEach { wisp ->
                    val xPos = ((wisp.xNorm + t * wisp.speedPerSec) % 1.4f) - 0.2f
                    val ovalW = wisp.widthFrac * w
                    val ovalH = wisp.heightFrac * h
                    drawOval(
                        color = Color.White.copy(alpha = (wisp.alpha * scaleAlpha).coerceAtMost(0.18f)),
                        topLeft = Offset(xPos * w - ovalW * 0.5f, wisp.yNorm * h),
                        size = Size(ovalW, ovalH)
                    )
                }
            }

            LiveEffectMode.FOG -> {
                particles.fogBands.forEach { band ->
                    val drift = sin(t * band.speedPerSec * 6f + band.phase) * 0.05f * w
                    val bandH = band.heightFrac * h
                    val topY = band.yNorm * h
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFFE0E7EF).copy(alpha = (band.alpha * scaleAlpha).coerceAtMost(0.20f)),
                                Color.Transparent
                            ),
                            startY = topY,
                            endY = topY + bandH
                        ),
                        topLeft = Offset(drift - w * 0.1f, topY),
                        size = Size(w * 1.2f, bandH)
                    )
                }
            }

            LiveEffectMode.LIGHT_RAIN,
            LiveEffectMode.RAIN,
            LiveEffectMode.HEAVY_RAIN,
            LiveEffectMode.THUNDERSTORM -> {
                if (effectMode == LiveEffectMode.THUNDERSTORM) {
                    particles.cloudWisps.forEach { wisp ->
                        val xPos = ((wisp.xNorm + t * wisp.speedPerSec) % 1.4f) - 0.2f
                        val ovalW = wisp.widthFrac * w
                        val ovalH = wisp.heightFrac * h
                        drawOval(
                            color = Color(0xFFB0BEC5).copy(alpha = (wisp.alpha * scaleAlpha).coerceAtMost(0.18f)),
                            topLeft = Offset(xPos * w - ovalW * 0.5f, wisp.yNorm * h),
                            size = Size(ovalW, ovalH)
                        )
                    }
                }

                val slantX = windLeanFactor * 22.dp.toPx()
                particles.rainDrops.forEach { drop ->
                    val yProgress = (drop.yNorm + t * drop.speedPerSec) % 1f
                    val xProgress = (drop.xNorm + t * drop.speedPerSec * windLeanFactor * 0.3f) % 1f
                    val startX = xProgress * w
                    val startY = yProgress * h
                    val lenPx = drop.lengthDp.dp.toPx()
                    drawLine(
                        color = Color(0xFFBBDEFB).copy(alpha = (drop.alpha * scaleAlpha).coerceAtMost(0.40f)),
                        start = Offset(startX, startY),
                        end = Offset(startX - slantX * 0.35f, startY + lenPx),
                        strokeWidth = drop.strokeDp.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                if (effectMode == LiveEffectMode.THUNDERSTORM) {
                    val cycle = t % 6.2f
                    val flashAlpha = when {
                        cycle in 0.0f..0.16f -> 0.20f * (1f - cycle / 0.16f)
                        cycle in 0.22f..0.34f -> 0.12f * (1f - (cycle - 0.22f) / 0.12f)
                        else -> 0f
                    }
                    if (flashAlpha > 0.01f) {
                        drawRect(
                            color = Color(0xFFE1F5FE).copy(alpha = (flashAlpha * scaleAlpha).coerceAtMost(0.20f))
                        )
                        val bx = w * 0.72f
                        boltPath.reset()
                        boltPath.moveTo(bx, h * 0.02f)
                        boltPath.lineTo(bx - w * 0.05f, h * 0.13f)
                        boltPath.lineTo(bx + w * 0.02f, h * 0.16f)
                        boltPath.lineTo(bx - w * 0.04f, h * 0.29f)
                        drawPath(
                            path = boltPath,
                            color = Color(0xFFFFF9C4).copy(alpha = (flashAlpha * 1.4f * scaleAlpha).coerceAtMost(0.28f)),
                            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }

            LiveEffectMode.SNOW,
            LiveEffectMode.BLIZZARD -> {
                val windDrift = if (effectMode == LiveEffectMode.BLIZZARD) 0.22f else 0.04f
                particles.snowFlakes.forEach { flake ->
                    val yProgress = (flake.yNorm + t * flake.speedPerSec) % 1f
                    val sway = sin(t * flake.driftFreq + flake.phase) * flake.driftAmp
                    val xProgress = ((flake.xNorm + t * windDrift + sway) % 1f + 1f) % 1f
                    drawCircle(
                        color = Color.White.copy(alpha = (flake.alpha * scaleAlpha).coerceAtMost(0.70f)),
                        radius = flake.radiusDp.dp.toPx(),
                        center = Offset(xProgress * w, yProgress * h)
                    )
                }
            }

            LiveEffectMode.NONE -> Unit
        }
    }
}
