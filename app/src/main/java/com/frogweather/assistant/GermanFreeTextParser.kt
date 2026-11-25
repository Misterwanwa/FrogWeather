package com.frogweather.assistant

import com.frogweather.WeatherType
import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.SportAdvisor
import com.frogweather.analysis.SportType
import com.frogweather.briefing.ClothingAdvisor
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class QueryIntentCategory(val displayLabel: String) {
    WEATHER_QUERY("Wetterfrage"),
    SPORT_WINDOW("Sportfenster"),
    EVENT_PLANNING("Terminplanung"),
    ROUTE_WEATHER("Routenwetter"),
    CLOTHING("Kleidungsempfehlung"),
    UNKNOWN("Allgemein / Unbekannt")
}

typealias AssistantIntentCategory = QueryIntentCategory

enum class TemporalRef {
    NOW,
    TODAY,
    TONIGHT,
    TOMORROW,
    WEEKEND,
    THIS_WEEK,
    SPECIFIC_HOUR,
    UNSPECIFIED
}

data class ParsedIntent(
    val category: QueryIntentCategory,
    val rawQuery: String,
    val normalizedQuery: String,
    val temporalRef: TemporalRef = TemporalRef.UNSPECIFIED,
    val targetHour: Int? = null,
    val locationHint: String? = null,
    val routeOrigin: String? = null,
    val routeDestination: String? = null,
    val sportHint: SportType? = null,
    val confidence: Double = 0.0,
    val matchedKeywords: List<String> = emptyList(),
    val responseHint: String
)

data class AssistantContext(
    val cityName: String?,
    val currentTempC: Double?,
    val feelsLikeC: Double?,
    val weatherType: WeatherType?,
    val windKmh: Double?,
    val uvIndex: Double?,
    val selectedSport: SportType,
    val hourlyPoints: List<HourlyWeatherPoint>,
    val calendarWarnings: List<String> = emptyList(),
    val lastRouteSummary: String? = null,
    val utcOffsetSeconds: Int = 0
)

data class AssistantAnswer(
    val intent: ParsedIntent,
    val title: String,
    val body: String
)

object GermanFreeTextParser {

    private val HOUR_REGEX = Regex("""\b(\d{1,2})(?::[0-5]\d\s*(?:uhr)?|\s*uhr)\b""")
    private val VON_NACH_REGEX = Regex(
        """\bvon\s+([a-zäöüß0-9\-\s]{2,30}?)\s+nach\s+([a-zäöüß0-9\-\s]{2,30}?)(?=\s+(?:heute|morgen|übermorgen|uebermorgen|um|mit\b|am\s+(?:montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|wochenende|morgen|abend|mittag|nachmittag|tag)\b|im\s+(?:regen|schnee|sommer|winter|herbst|frühling|fruehling)\b|losfahren|abfahrt|fahren|fahrt|route)|\?|$|\.|!)"""
    )
    private val NACH_DEST_REGEX = Regex(
        """\b(?:nach|richtung)\s+([a-zäöüß][a-zäöüß0-9\-\s]{1,28}?)(?=\s+(?:heute|morgen|übermorgen|uebermorgen|um|mit\b|am\s+(?:montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|wochenende|morgen|abend|mittag|nachmittag|tag)\b|im\s+(?:regen|schnee|sommer|winter|herbst|frühling|fruehling)\b|losfahren|abfahrt|fahren|fahrt|nach\b|richtung\b)|\?|$|\.|!)"""
    )
    private val IN_LOCATION_REGEX = Regex(
        """\bin\s+([a-zäöüß][a-zäöüß\-]{1,28})(?=\s|\?|$|\.|!)"""
    )

    private val ROUTE_WEG_PHRASE_REGEX = Regex(
        """\b(?:auf\s+dem\s+weg|(?:arbeits|heim|schul|hin|rück|rueck|fahrrad|rad|fuß|fuss)weg|(?:der|den|dem|ein|einen|einem|mein|meinen|meinem|dein|deinen|deinem|unser|unseren|unserem)\s+weg|weg\s+(?:nach|zur?|zum|in\s+die|ins|ans?))\b"""
    )
    private val KEYWORD_REGEX_MAP: Map<String, Regex> = mapOf(
        "rad" to Regex("""\brad\b"""),
        "weg" to Regex("""\bweg\b"""),
        "event" to Regex("""\bevent\b"""),
        "call" to Regex("""\bcall\b"""),
        "uv" to Regex("""\buv\b"""),
        "schirm" to Regex("""\bschirm\b"""),
        "baden" to Regex("""(?<![\p{L}\-])baden(?![\p{L}\-])"""),
        "berg" to Regex(
            """(?<!\p{L})berg(?:e|en|es|s|tour|touren|wandern|wanderung|wanderungen|gipfel|steig|steige|steigen|sport|welt|hütte|huette|kamm|grat|bahn|fahrt|see)?(?!\p{L})"""
        ),
        "ski" to Regex(
            """\bski(?:fahren|fahrer|fahrerin|tour|touren|piste|pisten|urlaub|lauf|langlauf|sport|gebiet|gebiete|pass|lift|lifte|hütte|huette|springen|sprung|wochenende|reise|saison)?\b"""
        )
    )

    private val LOCATION_STOPWORDS = setOf(
        "der", "die", "das", "den", "dem", "einem", "einer", "dieser", "früh", "frueh",
        "abend", "nacht", "stadt", "umgebung", "zukunft", "deutschland"
    )

    private val NON_DESTINATION_FIRST_WORDS = setOf(
        "der", "die", "das", "den", "dem", "des",
        "ein", "eine", "einem", "einen", "einer", "eines",
        "meinem", "meiner", "deinem", "deiner", "diesem", "dieser",
        "etwas", "was", "nichts", "allem", "allen",
        "oben", "unten", "vorne", "hinten", "links", "rechts",
        "draußen", "draussen", "drinnen", "hause", "haus"
    )

    private val NON_DESTINATION_NOUNS = setOf(
        "regen", "schnee", "schneefall", "gewitter", "sturm", "sonne", "sonnenschein",
        "wind", "frost", "nebel", "wetter", "hagel", "schauer", "niederschlag",
        "kälte", "kaelte", "hitze", "wärme", "waerme",
        "arbeit", "schule", "uni", "mittag", "mittags", "abend", "abends",
        "morgen", "vormittag", "nachmittag", "nacht", "mitternacht",
        "feiertag", "feierabend", "wochenende", "training", "sport", "spiel",
        "urlaub", "ferien", "pause", "oben", "unten", "hause"
    )

    private val CLOTHING_KEYWORDS = listOf(
        "was soll ich anziehen", "was anziehen", "anziehen", "kleidung", "klamotten",
        "jacke", "regenjacke", "winterjacke", "regenschirm", "taschenschirm", "schirm", "outfit",
        "pullover", "t-shirt", "kurze hose", "mütze", "muetze", "handschuhe", "sonnenbrille",
        "schichten", "zwiebelprinzip"
    )

    private val ROUTE_KEYWORDS = listOf(
        "route", "pendeln", "pendel", "arbeitsweg", "arbeitsstrecke", "strecke",
        "abfahrt", "losfahren", "abfahren", "fahrt nach", "fahren nach", "weg nach",
        "weg zur arbeit", "auf dem weg", "unterwegs nach"
    )

    private val EVENT_KEYWORDS = listOf(
        "kalender", "termin", "termine", "veranstaltung", "event", "meeting",
        "grillen", "picknick", "gartenparty", "umplanen", "verschieben",
        "kollidiert", "fällt ins wasser", "faellt ins wasser", "ausflug", "outdoor-termin"
    )

    private val SPORT_KEYWORDS = listOf(
        "sportfenster", "hours to skip", "sport", "training", "trainieren",
        "laufen", "joggen", "marathon", "radfahren", "radtour", "rennrad", "fahrrad",
        "wandern", "wanderung", "outdoor-fitness", "workout", "fitness",
        "fußball", "fussball", "teamsport", "schwimmen", "freibad", "wintersport",
        "ski", "snowboard", "spazieren", "spaziergang"
    )

    private val WEATHER_KEYWORDS = listOf(
        "wetter", "temperatur", "grad", "regnet", "regen", "niederschlag", "schauer",
        "schnee", "schneit", "sonne", "sonnig", "wind", "böen", "boeen", "sturm",
        "gewitter", "uv", "frost", "vorhersage", "prognose", "wie wird", "wird es",
        "kalt", "warm", "heiß", "heiss", "nebel", "bewölkt", "bewoelkt", "trocken", "nass"
    )

    private val SPORT_HINT_MAP: List<Pair<SportType, List<String>>> = listOf(
        SportType.RUNNING to listOf("laufen", "joggen", "laufrunde", "marathon"),
        SportType.CYCLING to listOf("radfahren", "fahrrad", "radtour", "rennrad", "e-bike", "ebike", "rad"),
        SportType.HIKING to listOf("wandern", "wanderung", "hike"),
        SportType.OUTDOOR_FITNESS to listOf("outdoor-fitness", "fitness", "workout", "calisthenics"),
        SportType.TEAM_SPORT to listOf("fußball", "fussball", "teamsport", "kicken", "soccer"),
        SportType.SWIMMING to listOf("schwimmen", "freibad", "badesee", "wassersport", "baden"),
        SportType.WINTER_SPORT to listOf("wintersport", "ski", "snowboard", "rodeln", "langlauf"),
        SportType.WALKING to listOf("spazieren", "spaziergang", "gassi")
    )

    private val TERRAIN_SPORT_HINT_MAP: List<Pair<SportType, List<String>>> = listOf(
        SportType.HIKING to listOf("berg", "alpen")
    )

    fun parseIntent(query: String): ParsedIntent {
        val raw = query.trim()
        if (raw.isBlank()) {
            return ParsedIntent(
                category = QueryIntentCategory.UNKNOWN,
                rawQuery = query,
                normalizedQuery = "",
                responseHint = "Bitte gib eine Frage auf Deutsch ein, z. B. „Wie wird das Wetter heute?“ oder „Was soll ich anziehen?“."
            )
        }

        val normalized = raw.lowercase(Locale.GERMAN).replace(Regex("""\s+"""), " ")
        val folded = foldUmlauts(normalized)

        val sportHint = detectSportHint(normalized, folded)
        val (temporalRef, targetHour) = detectTemporal(normalized, folded)
        val (routeOrigin, routeDestination) = detectRouteLocations(normalized)
        val locationHint = routeDestination ?: detectLocationHint(normalized)

        val matchedClothing = matchKeywords(normalized, folded, CLOTHING_KEYWORDS)
        val matchedRoute = matchKeywords(normalized, folded, ROUTE_KEYWORDS).toMutableList().apply {
            if (routeOrigin != null && routeDestination != null && !contains("von ... nach ...")) {
                add("von ... nach ...")
            }
        }
        val matchedEvent = matchKeywords(normalized, folded, EVENT_KEYWORDS)
        val matchedSport = matchKeywords(normalized, folded, SPORT_KEYWORDS)
        val matchedWeather = matchKeywords(normalized, folded, WEATHER_KEYWORDS)

        val scores = linkedMapOf(
            QueryIntentCategory.CLOTHING to matchedClothing.size * 3.2,
            QueryIntentCategory.ROUTE_WEATHER to matchedRoute.size * 2.8 +
                (if (routeDestination != null) 2.5 else 0.0) +
                (if (ROUTE_WEG_PHRASE_REGEX.containsMatchIn(normalized)) 1.5 else 0.0),
            QueryIntentCategory.EVENT_PLANNING to matchedEvent.size * 2.6,
            QueryIntentCategory.SPORT_WINDOW to matchedSport.size * 2.3 +
                (if (sportHint != null && matchedClothing.isEmpty() && matchedEvent.isEmpty()) 1.5 else 0.0),
            QueryIntentCategory.WEATHER_QUERY to matchedWeather.size * 2.0
        )

        // Specific precedence boosts for natural German phrasing
        if (normalized.contains("anziehen") || normalized.contains("kleidung")) {
            scores[QueryIntentCategory.CLOTHING] = (scores[QueryIntentCategory.CLOTHING] ?: 0.0) + 4.0
        }
        if (normalized.contains("kalender") || normalized.contains("termin") ||
            normalized.contains("verschieben") || normalized.contains("ins wasser")
        ) {
            scores[QueryIntentCategory.EVENT_PLANNING] = (scores[QueryIntentCategory.EVENT_PLANNING] ?: 0.0) + 3.0
        }
        if (normalized.contains("route") || normalized.contains("arbeitsweg") ||
            normalized.contains("losfahren") || normalized.contains("abfahrt") ||
            normalized.contains("auf dem weg")
        ) {
            scores[QueryIntentCategory.ROUTE_WEATHER] = (scores[QueryIntentCategory.ROUTE_WEATHER] ?: 0.0) + 3.0
        }

        val bestEntry = scores.entries.maxByOrNull { it.value }
        val totalScore = scores.values.sum()
        if (bestEntry == null || bestEntry.value <= 0.0) {
            return ParsedIntent(
                category = QueryIntentCategory.UNKNOWN,
                rawQuery = query,
                normalizedQuery = normalized,
                temporalRef = temporalRef,
                targetHour = targetHour,
                locationHint = locationHint,
                confidence = 0.0,
                matchedKeywords = emptyList(),
                responseHint = "Ich habe deine Anfrage nicht eindeutig erkannt. Frage z. B. nach Wetter, Kleidung („Was anziehen?“), Sportfenster, Kalender-Terminen oder deiner Route."
            )
        }

        val category = bestEntry.key
        val confidence = (bestEntry.value / totalScore.coerceAtLeast(1.0)).coerceIn(0.2, 1.0)
        val matched = when (category) {
            QueryIntentCategory.CLOTHING -> matchedClothing
            QueryIntentCategory.ROUTE_WEATHER -> matchedRoute
            QueryIntentCategory.EVENT_PLANNING -> matchedEvent
            QueryIntentCategory.SPORT_WINDOW -> matchedSport
            QueryIntentCategory.WEATHER_QUERY -> matchedWeather
            QueryIntentCategory.UNKNOWN -> emptyList()
        }

        val hint = when (category) {
            QueryIntentCategory.WEATHER_QUERY ->
                "Wetterabfrage${locationHint?.let { " für $it" } ?: ""} erkannt."
            QueryIntentCategory.SPORT_WINDOW ->
                "Sportfenster-Anfrage (${(sportHint ?: SportType.RUNNING).displayName}) erkannt."
            QueryIntentCategory.EVENT_PLANNING ->
                "Kalender- & Terminprüfung erkannt."
            QueryIntentCategory.ROUTE_WEATHER ->
                "Routenwetter- & Abfahrtsanfrage${routeDestination?.let { " nach $it" } ?: ""} erkannt."
            QueryIntentCategory.CLOTHING ->
                "Kleidungsempfehlung („Was soll ich anziehen?“) erkannt."
            QueryIntentCategory.UNKNOWN ->
                "Keine passende Kategorie gefunden."
        }

        return ParsedIntent(
            category = category,
            rawQuery = query,
            normalizedQuery = normalized,
            temporalRef = temporalRef,
            targetHour = targetHour,
            locationHint = locationHint,
            routeOrigin = routeOrigin,
            routeDestination = routeDestination,
            sportHint = sportHint,
            confidence = confidence,
            matchedKeywords = matched,
            responseHint = hint
        )
    }

    private fun foldUmlauts(text: String): String = text
        .replace("ä", "ae")
        .replace("ö", "oe")
        .replace("ü", "ue")
        .replace("ß", "ss")

    private fun matchesKeyword(normalized: String, folded: String, keyword: String): Boolean {
        val kwFolded = foldUmlauts(keyword)
        val wordRegex = KEYWORD_REGEX_MAP[keyword]
        return if (wordRegex != null) {
            val foldedRegex = KEYWORD_REGEX_MAP[kwFolded] ?: wordRegex
            wordRegex.containsMatchIn(normalized) || foldedRegex.containsMatchIn(folded)
        } else {
            normalized.contains(keyword) || folded.contains(kwFolded)
        }
    }

    private fun matchKeywords(normalized: String, folded: String, keywords: List<String>): List<String> {
        return keywords.filter { kw -> matchesKeyword(normalized, folded, kw) }
    }

    private fun detectSportHint(normalized: String, folded: String): SportType? {
        for ((sport, words) in SPORT_HINT_MAP) {
            if (words.any { w -> matchesKeyword(normalized, folded, w) }) {
                return sport
            }
        }
        for ((sport, words) in TERRAIN_SPORT_HINT_MAP) {
            if (words.any { w -> matchesKeyword(normalized, folded, w) }) {
                return sport
            }
        }
        return null
    }

    private fun detectTemporal(normalized: String, folded: String): Pair<TemporalRef, Int?> {
        val hourMatch = HOUR_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val validHour = hourMatch?.takeIf { it in 0..23 }
        if (validHour != null) {
            return TemporalRef.SPECIFIC_HOUR to validHour
        }
        val ref = when {
            normalized.contains("übermorgen") || folded.contains("uebermorgen") ||
                normalized.contains("morgen") -> TemporalRef.TOMORROW
            normalized.contains("heute abend") || normalized.contains("abend") ||
                normalized.contains("nacht") -> TemporalRef.TONIGHT
            normalized.contains("jetzt") || normalized.contains("gerade") ||
                normalized.contains("aktuell") -> TemporalRef.NOW
            normalized.contains("heute") -> TemporalRef.TODAY
            normalized.contains("wochenende") || normalized.contains("samstag") ||
                normalized.contains("sonntag") -> TemporalRef.WEEKEND
            normalized.contains("woche") || normalized.contains("nächste tage") ||
                folded.contains("naechste tage") -> TemporalRef.THIS_WEEK
            else -> TemporalRef.UNSPECIFIED
        }
        return ref to null
    }

    private fun detectRouteLocations(normalized: String): Pair<String?, String?> {
        val vonNach = VON_NACH_REGEX.find(normalized)
        if (vonNach != null) {
            val origin = cleanRoutePlace(vonNach.groupValues[1])
            val dest = cleanRoutePlace(vonNach.groupValues[2])
            if (origin != null && dest != null) return origin to dest
        }
        for (nach in NACH_DEST_REGEX.findAll(normalized)) {
            val dest = cleanRoutePlace(nach.groupValues[1])
            if (dest != null) return null to dest
        }
        return null to null
    }

    private fun cleanRoutePlace(raw: String): String? {
        val cleaned = raw.trim().trimEnd('?', '.', '!', ',')
        if (cleaned.length < 2) return null
        val tokens = cleaned.lowercase(Locale.GERMAN)
            .split(Regex("""[\s\-]+"""))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        if (tokens.first() in NON_DESTINATION_FIRST_WORDS) return null
        if (tokens.any { it in NON_DESTINATION_NOUNS }) return null
        return cleanPlace(cleaned)
    }

    private fun detectLocationHint(normalized: String): String? {
        val match = IN_LOCATION_REGEX.find(normalized) ?: return null
        val candidate = match.groupValues[1].trim()
        if (candidate.lowercase(Locale.GERMAN) in LOCATION_STOPWORDS) return null
        return cleanPlace(candidate)
    }

    private fun cleanPlace(raw: String): String? {
        val cleaned = raw.trim().trimEnd('?', '.', '!', ',')
        if (cleaned.length < 2) return null
        return cleaned.split(" ").joinToString(" ") { part ->
            part.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.GERMAN) else ch.toString()
            }
        }
    }
}

object AssistantResponseEngine {

    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)

    @JvmOverloads
    fun answer(
        query: String,
        context: AssistantContext,
        nowMillis: Long = System.currentTimeMillis()
    ): AssistantAnswer {
        val intent = GermanFreeTextParser.parseIntent(query)
        val city = context.cityName?.takeIf { it.isNotBlank() } ?: "deinem Standort"
        val sportForDedupe = intent.sportHint ?: context.selectedSport
        val points = SportAdvisor.evaluateHoursToSkip(sportForDedupe, context.hourlyPoints)
            .allEvaluations
            .map { it.hour }
        val offset = runCatching { ZoneOffset.ofTotalSeconds(context.utcOffsetSeconds) }
            .getOrDefault(ZoneOffset.UTC)
        val nowDate = Instant.ofEpochMilli(nowMillis).atOffset(offset).toLocalDate()
        val tomorrowDate = nowDate.plusDays(1)
        val validCurrentTemp = context.currentTempC?.takeIf { it.isFinite() }

        return when (intent.category) {
            QueryIntentCategory.WEATHER_QUERY -> {
                val body = if (points.isNotEmpty()) {
                    val slice = when (intent.temporalRef) {
                        TemporalRef.TOMORROW -> {
                            points.filter {
                                Instant.ofEpochMilli(it.epochMillis).atOffset(offset).toLocalDate() == tomorrowDate
                            }.ifEmpty { points.drop(12).take(24).ifEmpty { points } }
                        }
                        else -> points.filter { it.epochMillis >= nowMillis }.ifEmpty { points }.take(24)
                    }
                    val finiteTemps = slice.map { it.tempC }.filter { it.isFinite() }
                    val finiteWinds = slice.map { it.windKmh }.filter { it.isFinite() && it >= 0.0 }
                    val tempRangeText = if (finiteTemps.isNotEmpty()) {
                        String.format(Locale.GERMAN, "%.0f–%.0f °C", finiteTemps.min(), finiteTemps.max())
                    } else {
                        "-- °C"
                    }
                    val currentText = validCurrentTemp?.let { "${it.toInt()} °C" } ?: "--"
                    val maxPop = slice.maxOfOrNull { it.precipProb.coerceIn(0, 100) } ?: 0
                    val maxWind = finiteWinds.maxOrNull() ?: 0.0
                    String.format(
                        Locale.GERMAN,
                        "In %s erwarten dich %s (aktuell %s), Regenrisiko bis %d %%, Wind bis %.0f km/h.",
                        city,
                        tempRangeText,
                        currentText,
                        maxPop,
                        maxWind
                    )
                } else {
                    val tempStr = validCurrentTemp?.let { "${it.toInt()} °C" } ?: "--"
                    val condStr = context.weatherType?.label ?: "keine Detaildaten"
                    "Aktuell in $city: $tempStr ($condStr)."
                }
                AssistantAnswer(intent = intent, title = "Wetter für $city", body = body)
            }

            QueryIntentCategory.SPORT_WINDOW -> {
                val sport = intent.sportHint ?: context.selectedSport
                val futurePoints = when (intent.temporalRef) {
                    TemporalRef.TOMORROW -> {
                        points.filter {
                            it.epochMillis >= nowMillis &&
                                Instant.ofEpochMilli(it.epochMillis).atOffset(offset).toLocalDate() == tomorrowDate
                        }.ifEmpty { points.filter { it.epochMillis >= nowMillis }.take(24) }
                    }
                    else -> points.filter { it.epochMillis >= nowMillis }.take(24)
                }
                val body = if (futurePoints.isNotEmpty()) {
                    val best = SportAdvisor.findBestSportWindow(sport, futurePoints, context.utcOffsetSeconds)
                    val verdict = SportAdvisor.evaluateHoursToSkip(sport, futurePoints)
                    if (best != null) {
                        val startStr = Instant.ofEpochMilli(best.startEpochMillis).atOffset(offset).format(TIME_FMT)
                        val endStr = Instant.ofEpochMilli(best.endEpochMillis).atOffset(offset).format(TIME_FMT)
                        "Bestes Zeitfenster für ${sport.displayName}: $startStr–$endStr Uhr (${best.summaryLabel}). ${verdict.summaryText}"
                    } else {
                        "Für ${sport.displayName} gibt es aktuell kein ideales Outdoor-Zeitfenster: ${verdict.summaryText}"
                    }
                } else {
                    "Für ${sport.displayName} werden Stundendaten geladen – bitte kurz aktualisieren."
                }
                AssistantAnswer(intent = intent, title = "Sportfenster (${sport.displayName})", body = body)
            }

            QueryIntentCategory.CLOTHING -> {
                val isTomorrow = intent.temporalRef == TemporalRef.TOMORROW
                val nextHours = if (isTomorrow) {
                    points.filter {
                        Instant.ofEpochMilli(it.epochMillis).atOffset(offset).toLocalDate() == tomorrowDate
                    }.ifEmpty { points.drop(12).take(24).ifEmpty { points } }
                } else {
                    points.filter { it.epochMillis >= nowMillis }.ifEmpty { points }.take(12)
                }
                val finiteTemps = nextHours.map { it.tempC }.filter { it.isFinite() }
                val temp = if (isTomorrow) {
                    finiteTemps.minOrNull() ?: validCurrentTemp ?: 15.0
                } else {
                    validCurrentTemp ?: finiteTemps.firstOrNull() ?: 15.0
                }
                val feels = if (isTomorrow) {
                    temp
                } else {
                    context.feelsLikeC?.takeIf { it.isFinite() } ?: temp
                }
                val prob = nextHours.maxOfOrNull { it.precipProb.coerceIn(0, 100) }
                    ?: if (!isTomorrow && context.weatherType == WeatherType.RAINY) 80 else 15
                val precipMm = nextHours.map { it.precipMm }
                    .filter { it.isFinite() && it >= 0.0 }
                    .maxOrNull()
                    ?: 0.0
                val wind = if (isTomorrow) {
                    nextHours.map { it.windKmh }.filter { it.isFinite() && it >= 0.0 }.maxOrNull() ?: 10.0
                } else {
                    context.windKmh?.takeIf { it.isFinite() && it >= 0.0 }
                        ?: nextHours.map { it.windKmh }.filter { it.isFinite() && it >= 0.0 }.maxOrNull()
                        ?: 10.0
                }
                val uv = if (isTomorrow) {
                    nextHours.map { it.uvIndex }.filter { it.isFinite() && it >= 0.0 }.maxOrNull() ?: 2.0
                } else {
                    context.uvIndex?.takeIf { it.isFinite() && it >= 0.0 }
                        ?: nextHours.map { it.uvIndex }.filter { it.isFinite() && it >= 0.0 }.maxOrNull()
                        ?: 2.0
                }
                val rec = ClothingAdvisor.recommendClothing(temp, feels, prob, wind, uv, precipMm = precipMm)
                AssistantAnswer(
                    intent = intent,
                    title = "Kleidungsempfehlung",
                    body = "${rec.headline}. ${rec.summary}"
                )
            }

            QueryIntentCategory.EVENT_PLANNING -> {
                val body = if (context.calendarWarnings.isNotEmpty()) {
                    context.calendarWarnings.joinToString("\n• ", prefix = "• ")
                } else {
                    "Keine wetterkritischen Outdoor-Termine im Kalender gefunden (oder Kalenderberechtigung unten im Planer aktivieren)."
                }
                AssistantAnswer(intent = intent, title = "Kalender- & Termincheck", body = body)
            }

            QueryIntentCategory.ROUTE_WEATHER -> {
                val dest = intent.routeDestination
                val body = when {
                    !context.lastRouteSummary.isNullOrBlank() && dest == null -> context.lastRouteSummary
                    dest != null -> "Ziel „$dest“ erkannt. Starte die Routenberechnung im Abschnitt „Routenwetter & Abfahrtsrechner“ unten für OSRM-Strecke & optimale Abfahrtszeit."
                    else -> "Nutze den Routenrechner unten im Planer, um Start, Ziel und Verkehrsmittel (Auto/Fahrrad/Zu Fuß) zu prüfen."
                }
                AssistantAnswer(intent = intent, title = "Routenwetter", body = body)
            }

            QueryIntentCategory.UNKNOWN -> {
                AssistantAnswer(
                    intent = intent,
                    title = "Hinweis (Lokaler Assistent)",
                    body = intent.responseHint
                )
            }
        }
    }
}
