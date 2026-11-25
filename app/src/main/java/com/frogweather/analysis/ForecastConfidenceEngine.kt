package com.frogweather.analysis

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class HourlyConfidence(
    val hourIndex: Int,
    val percent: Int,
    val tierLabel: String,
    val formattedLabel: String,
    val activeModels: List<String>,
    val dominantDivergenceParam: String?
)

data class ConfidenceResult(
    val percent: Int,
    val tierLabel: String,
    val formattedLabel: String,
    val activeModels: List<String>,
    val excludedModels: List<String>,
    val hasEnoughModels: Boolean,
    val hourlyConfidences: List<HourlyConfidence>,
    val divergenceExplanation: String
)

object ForecastConfidenceEngine {

    private data class DivergencePeak(
        val hourIndex: Int,
        val paramName: String,
        val unit: String,
        val penalty: Double,
        val minModel: String,
        val minValue: Double,
        val maxModel: String,
        val maxValue: Double
    )

    fun modelDisplayName(modelId: String): String = when (modelId.lowercase(Locale.ROOT)) {
        "ecmwf_ifs025", "ecmwf" -> "ECMWF"
        "gfs_global", "gfs" -> "GFS"
        "icon_d2", "icon-d2" -> "ICON-D2"
        "owm" -> "OWM"
        "best_match" -> "Best Match"
        else -> modelId.uppercase(Locale.ROOT)
    }

    fun computeConfidence(
        modelTemps: Map<String, List<Double?>>,
        modelPrecips: Map<String, List<Double?>>,
        modelWinds: Map<String, List<Double?>>
    ): ConfidenceResult {
        val allModels = linkedSetOf<String>().apply {
            addAll(modelTemps.keys)
            addAll(modelPrecips.keys)
            addAll(modelWinds.keys)
        }

        val activeModels = mutableListOf<String>()
        val excludedModels = mutableListOf<String>()

        for (model in allModels) {
            val hasFinite = hasAnyFinite(modelTemps[model]) ||
                hasAnyFinite(modelPrecips[model]) ||
                hasAnyFinite(modelWinds[model])
            if (hasFinite) {
                activeModels.add(model)
            } else {
                excludedModels.add(model)
            }
        }

        if (activeModels.isEmpty()) {
            return ConfidenceResult(
                percent = 0,
                tierLabel = "Unsicher",
                formattedLabel = "0 % · Unsicher",
                activeModels = emptyList(),
                excludedModels = excludedModels,
                hasEnoughModels = false,
                hourlyConfidences = emptyList(),
                divergenceExplanation = "Keine Modelldaten für einen Vergleich verfügbar."
            )
        }

        if (activeModels.size < 2) {
            val onlyName = modelDisplayName(activeModels.first())
            val exclusionNote = buildExclusionNote(excludedModels)
            return ConfidenceResult(
                percent = 45,
                tierLabel = "Unsicher",
                formattedLabel = "45 % · Unsicher",
                activeModels = activeModels,
                excludedModels = excludedModels,
                hasEnoughModels = false,
                hourlyConfidences = emptyList(),
                divergenceExplanation = "Nur 1 Modell ($onlyName) verfügbar – kein Multi-Modell-Vergleich möglich.$exclusionNote"
            )
        }

        val maxHours = activeModels.maxOf { model ->
            maxOf(
                modelTemps[model]?.size ?: 0,
                modelPrecips[model]?.size ?: 0,
                modelWinds[model]?.size ?: 0
            )
        }

        if (maxHours == 0) {
            return ConfidenceResult(
                percent = 0,
                tierLabel = "Unsicher",
                formattedLabel = "0 % · Unsicher",
                activeModels = activeModels,
                excludedModels = excludedModels,
                hasEnoughModels = false,
                hourlyConfidences = emptyList(),
                divergenceExplanation = "Keine stündlichen Zeitreihen für einen Modellvergleich vorhanden."
            )
        }

        val hourlyResults = ArrayList<HourlyConfidence>(maxHours)
        val multiModelPercents = mutableListOf<Int>()
        val peaksByParam = linkedMapOf<String, DivergencePeak>()

        for (h in 0 until maxHours) {
            val tempsAtH = collectAtHour(activeModels, modelTemps, h)
            val precipsAtH = collectAtHour(activeModels, modelPrecips, h)
            val windsAtH = collectAtHour(activeModels, modelWinds, h)

            val hourActive = linkedSetOf<String>().apply {
                addAll(tempsAtH.keys)
                addAll(precipsAtH.keys)
                addAll(windsAtH.keys)
            }.toList()

            val hasPair = tempsAtH.size >= 2 || precipsAtH.size >= 2 || windsAtH.size >= 2
            if (!hasPair) {
                hourlyResults.add(
                    HourlyConfidence(
                        hourIndex = h,
                        percent = 45,
                        tierLabel = "Unsicher",
                        formattedLabel = "45 % · Unsicher",
                        activeModels = hourActive,
                        dominantDivergenceParam = null
                    )
                )
                continue
            }

            val tempPenalty = if (tempsAtH.size >= 2) {
                (populationStdDev(tempsAtH.values) * 12.0).coerceIn(0.0, 45.0)
            } else {
                0.0
            }

            val precipPenalty = if (precipsAtH.size >= 2) {
                val values = precipsAtH.values
                val wetCount = values.count { it >= 0.2 }
                val dryCount = values.count { it < 0.1 }
                val splitPenalty = if (wetCount > 0 && dryCount > 0) 12.0 else 0.0
                (splitPenalty + (populationStdDev(values) * 12.0).coerceIn(0.0, 25.0)).coerceIn(0.0, 35.0)
            } else {
                0.0
            }

            val windPenalty = if (windsAtH.size >= 2) {
                (populationStdDev(windsAtH.values) * 1.5).coerceIn(0.0, 20.0)
            } else {
                0.0
            }

            val totalPenalty = (tempPenalty + precipPenalty + windPenalty).coerceIn(0.0, 100.0)
            val hourPercent = (100.0 - totalPenalty).roundToInt().coerceIn(0, 100)
            val tier = tierForPercent(hourPercent)

            val paramPenalties = listOf(
                Triple("Temperatur", tempPenalty, tempsAtH) to "°C",
                Triple("Niederschlag", precipPenalty, precipsAtH) to "mm",
                Triple("Wind", windPenalty, windsAtH) to "km/h"
            )

            paramPenalties.forEach { (triple, unit) ->
                val (paramName, pVal, mapAtH) = triple
                if (mapAtH.size >= 2 && pVal > 0.0) {
                    val minEntry = mapAtH.minByOrNull { it.value }!!
                    val maxEntry = mapAtH.maxByOrNull { it.value }!!
                    val prev = peaksByParam[paramName]
                    if (prev == null || pVal > prev.penalty) {
                        peaksByParam[paramName] = DivergencePeak(
                            hourIndex = h,
                            paramName = paramName,
                            unit = unit,
                            penalty = pVal,
                            minModel = modelDisplayName(minEntry.key),
                            minValue = minEntry.value,
                            maxModel = modelDisplayName(maxEntry.key),
                            maxValue = maxEntry.value
                        )
                    }
                }
            }

            val dominantParam = paramPenalties
                .map { it.first }
                .filter { it.second >= 6.0 }
                .maxByOrNull { it.second }
                ?.first

            multiModelPercents.add(hourPercent)
            hourlyResults.add(
                HourlyConfidence(
                    hourIndex = h,
                    percent = hourPercent,
                    tierLabel = tier,
                    formattedLabel = "$hourPercent % · $tier",
                    activeModels = hourActive,
                    dominantDivergenceParam = dominantParam
                )
            )
        }

        if (multiModelPercents.isEmpty()) {
            val exclusionNote = buildExclusionNote(excludedModels)
            return ConfidenceResult(
                percent = 45,
                tierLabel = "Unsicher",
                formattedLabel = "45 % · Unsicher",
                activeModels = activeModels,
                excludedModels = excludedModels,
                hasEnoughModels = false,
                hourlyConfidences = hourlyResults,
                divergenceExplanation = "Zu keiner Stunde liegen mindestens 2 gültige Modelle gleichzeitig vor.$exclusionNote"
            )
        }

        val overallPercent = multiModelPercents.average().roundToInt().coerceIn(0, 100)
        val overallTier = tierForPercent(overallPercent)
        val explanation = buildExplanation(
            overallTier = overallTier,
            activeModels = activeModels,
            excludedModels = excludedModels,
            peaksByParam = peaksByParam
        )

        return ConfidenceResult(
            percent = overallPercent,
            tierLabel = overallTier,
            formattedLabel = "$overallPercent % · $overallTier",
            activeModels = activeModels,
            excludedModels = excludedModels,
            hasEnoughModels = true,
            hourlyConfidences = hourlyResults,
            divergenceExplanation = explanation
        )
    }

    private fun tierForPercent(percent: Int): String = when {
        percent >= 75 -> "Hoch"
        percent >= 50 -> "Mittel"
        else -> "Unsicher"
    }

    private fun hasAnyFinite(series: List<Double?>?): Boolean =
        series != null && series.any { it != null && it.isFinite() }

    private fun collectAtHour(
        models: List<String>,
        data: Map<String, List<Double?>>,
        hourIndex: Int
    ): Map<String, Double> {
        val result = linkedMapOf<String, Double>()
        for (model in models) {
            val value = data[model]?.getOrNull(hourIndex)
            if (value != null && value.isFinite()) {
                result[model] = value
            }
        }
        return result
    }

    private fun populationStdDev(values: Collection<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { v ->
            val diff = v - mean
            diff * diff
        } / values.size
        return sqrt(variance)
    }

    private fun buildExclusionNote(excludedModels: List<String>): String {
        if (excludedModels.isEmpty()) return ""
        val names = excludedModels.joinToString(", ") { modelDisplayName(it) }
        return if ("icon_d2" in excludedModels) {
            " ($names außerhalb des Abdeckungsgebiets ausgeschlossen.)"
        } else {
            " ($names ohne gültige Daten ausgeschlossen.)"
        }
    }

    private fun buildExplanation(
        overallTier: String,
        activeModels: List<String>,
        excludedModels: List<String>,
        peaksByParam: Map<String, DivergencePeak>
    ): String {
        val modelNames = activeModels.joinToString(", ") { modelDisplayName(it) }
        val exclusionNote = buildExclusionNote(excludedModels)
        val significantPeaks = peaksByParam.values
            .filter { it.penalty >= 6.0 }
            .sortedByDescending { it.penalty }
            .take(2)

        if (overallTier == "Hoch" && significantPeaks.isEmpty()) {
            return "Hohe Übereinstimmung zwischen $modelNames.$exclusionNote"
        }

        if (significantPeaks.isEmpty()) {
            return "Moderate Streuung zwischen $modelNames.$exclusionNote"
        }

        val details = significantPeaks.joinToString(" und ") { peak ->
            val pattern = if (peak.unit == "km/h") "%.0f" else "%.1f"
            val minFormatted = String.format(Locale.GERMAN, pattern, peak.minValue)
            val maxFormatted = String.format(Locale.GERMAN, pattern, peak.maxValue)
            "${peak.paramName} (${peak.minModel} $minFormatted ${peak.unit} vs. ${peak.maxModel} $maxFormatted ${peak.unit})"
        }

        val prefix = if (overallTier == "Hoch") {
            "Gute Übereinstimmung zwischen $modelNames, leichte Abweichung bei "
        } else {
            "Abweichung bei "
        }
        return "$prefix$details.$exclusionNote"
    }
}
