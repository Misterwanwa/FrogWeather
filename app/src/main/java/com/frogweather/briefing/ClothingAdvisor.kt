package com.frogweather.briefing

import java.util.Locale

enum class ThermalTier(val label: String) {
    FREEZING("Eisig"),
    COLD("Kalt"),
    COOL("Kühl"),
    MILD("Mild"),
    WARM("Warm"),
    HOT("Heiß")
}

data class ClothingRecommendation(
    val effectiveTempC: Double,
    val thermalTier: ThermalTier,
    val headline: String,
    val layers: List<String>,
    val rainGear: List<String>,
    val windAdvice: String?,
    val sunOrColdProtection: String?,
    val needsRainGear: Boolean,
    val needsWindProtection: Boolean,
    val needsSunProtection: Boolean,
    val needsColdProtection: Boolean,
    val summary: String
) {
    val fullSummary: String
        get() = summary
}

object ClothingAdvisor {

    fun recommendClothing(
        tempC: Double,
        feelsLikeC: Double,
        precipProb: Int,
        windKmh: Double,
        uvIndex: Double,
        precipMm: Double = 0.0
    ): ClothingRecommendation {
        val safeTemp = if (tempC.isFinite()) tempC else 15.0
        val rawFeels = if (feelsLikeC.isFinite()) feelsLikeC else safeTemp
        val safeWind = if (windKmh.isFinite() && windKmh >= 0.0) windKmh else 0.0
        val safePrecipProb = precipProb.coerceIn(0, 100)
        val safePrecipMm = if (precipMm.isFinite() && precipMm >= 0.0) precipMm else 0.0
        val safeUv = if (uvIndex.isFinite() && uvIndex >= 0.0) uvIndex else 0.0

        val effectiveTemp = if (rawFeels == safeTemp && safeWind >= 30.0 && safeTemp <= 12.0) {
            safeTemp - 1.5
        } else {
            rawFeels
        }

        val tier = when {
            effectiveTemp < 0.0 -> ThermalTier.FREEZING
            effectiveTemp < 8.0 -> ThermalTier.COLD
            effectiveTemp < 15.0 -> ThermalTier.COOL
            effectiveTemp < 21.0 -> ThermalTier.MILD
            effectiveTemp < 27.0 -> ThermalTier.WARM
            else -> ThermalTier.HOT
        }

        val layers = when (tier) {
            ThermalTier.FREEZING -> mutableListOf(
                "Thermo-Unterwäsche",
                "Warmer Fleece- oder Wollpullover",
                "Gefütterte Winterjacke",
                "Lange gefütterte Hose & Winterschuhe",
                "Mütze, Schal und Handschuhe"
            )
            ThermalTier.COLD -> mutableListOf(
                "Langarmshirt",
                "Warmer Pullover oder Fleece",
                "Übergangs- oder Winterjacke",
                "Lange Hose & geschlossene Schuhe"
            )
            ThermalTier.COOL -> mutableListOf(
                "T-Shirt oder dünnes Langarmshirt",
                "Leichter Pullover oder Strickjacke",
                "Leichte Jacke oder Weste",
                "Lange Hose & Sneaker"
            )
            ThermalTier.MILD -> mutableListOf(
                "Atmungsaktives T-Shirt",
                "Leichte Jacke oder Hoodie für den Schatten",
                "Lange leichte Hose oder Chinos"
            )
            ThermalTier.WARM -> mutableListOf(
                "Luftiges T-Shirt oder Top",
                "Kurze Hose oder leichter Rock/Leinenhose",
                "Leichtes Schuhwerk"
            )
            ThermalTier.HOT -> mutableListOf(
                "Sehr leichte, helle Baumwoll- oder Leinenkleidung",
                "Kurze Hose / luftiges Sommer-Outfit",
                "Offene, bequeme Schuhe"
            )
        }

        val needsRainGear = safePrecipProb >= 30 || safePrecipMm >= 0.3
        val heavyRain = safePrecipProb >= 60 || safePrecipMm >= 1.5
        val rainGear = mutableListOf<String>()
        if (heavyRain) {
            rainGear.add("Wasserdichte Regenjacke")
            rainGear.add("Wasserabweisende Schuhe")
            if (safeWind < 45.0) {
                rainGear.add("Regenschirm")
            }
        } else if (needsRainGear) {
            if (safeWind >= 45.0) {
                rainGear.add("Leichte Kapuzenjacke / Regenjacke (Schirm meiden)")
            } else {
                rainGear.add("Taschenschirm oder leichte Kapuzenjacke")
            }
        }

        val needsWindProtection = safeWind >= 28.0
        val windAdvice = when {
            safeWind >= 45.0 -> "Starker Wind (${safeWind.toInt()} km/h): winddichte Hardshell tragen, Regenschirm meiden."
            safeWind >= 28.0 -> "Spürbarer Wind (${safeWind.toInt()} km/h): winddichte Jacke oder Softshell empfohlen."
            else -> null
        }

        val needsSunProtection = safeUv >= 3.0
        val needsColdProtection = effectiveTemp < 2.0
        val sunOrColdProtection = buildList {
            if (safeUv >= 8.0) {
                add("Hoher UV-Index (${String.format(Locale.GERMAN, "%.1f", safeUv)}): Sonnenschutz LSF 50+, Sonnenbrille & Kopfbedeckung")
            } else if (safeUv >= 3.0) {
                add("UV-Schutz empfohlen: Sonnencreme LSF 30+ & Sonnenbrille")
            }
            if (needsColdProtection) {
                add("Kälteschutz: Mütze, Schal und warme Handschuhe einpacken")
            }
        }.takeIf { it.isNotEmpty() }?.joinToString(" • ")

        val headline = when {
            heavyRain && tier <= ThermalTier.COLD ->
                "Warm & regendicht anziehen (${tier.label}, ${String.format(Locale.GERMAN, "%.0f", effectiveTemp)} °C gefühlt)"
            heavyRain ->
                "Regenkleidung einpacken (${tier.label}, ${String.format(Locale.GERMAN, "%.0f", effectiveTemp)} °C gefühlt)"
            needsColdProtection ->
                "Winterfest kleiden (${String.format(Locale.GERMAN, "%.0f", effectiveTemp)} °C gefühlt)"
            tier == ThermalTier.HOT && needsSunProtection ->
                "Luftig kleiden & starken Sonnenschutz nutzen (${String.format(Locale.GERMAN, "%.0f", effectiveTemp)} °C)"
            else ->
                "${tier.label} (${String.format(Locale.GERMAN, "%.0f", effectiveTemp)} °C gefühlt): ${layers.take(2).joinToString(" + ")}"
        }

        val summaryParts = mutableListOf<String>()
        summaryParts.add("Basis: ${layers.joinToString(", ")}")
        if (rainGear.isNotEmpty()) {
            summaryParts.add("Regenschutz: ${rainGear.joinToString(", ")}")
        }
        if (windAdvice != null) {
            summaryParts.add(windAdvice)
        }
        if (sunOrColdProtection != null) {
            summaryParts.add(sunOrColdProtection)
        }

        return ClothingRecommendation(
            effectiveTempC = effectiveTemp,
            thermalTier = tier,
            headline = headline,
            layers = layers,
            rainGear = rainGear,
            windAdvice = windAdvice,
            sunOrColdProtection = sunOrColdProtection,
            needsRainGear = needsRainGear,
            needsWindProtection = needsWindProtection,
            needsSunProtection = needsSunProtection,
            needsColdProtection = needsColdProtection,
            summary = summaryParts.joinToString(" · ")
        )
    }
}
