package com.shiv.syncnavigator.navigation.parser

/**
 * PHASE 2 / M5 — pulls distance, ETA and road name out of notification text.
 *
 * Pure string functions, no Android. Every rule below comes from the 745-record
 * corpus; nothing is anticipated.
 *
 * ASCII-only output is a hard requirement, not tidiness. Google Maps emits
 * `100\u00A0m` (non-breaking space), `5:12\u202Fam` (narrow no-break space) and
 * `·` separators. SYNC 1 advertises characterSet=CID1SET and will not render
 * those. Everything leaving this file is plain ASCII.
 */
object FieldExtractors {

    /** Every space-like character Maps uses, collapsed to a plain space. */
    private val SPACES = Regex("[\\u00A0\\u202F\\u2009\\u2007\\s]+")

    private fun ascii(s: String): String = SPACES.replace(s, " ").trim()

    // ---- distance -----------------------------------------------------------

    /** `40 m`, `900 m`, `2.5 km`, `1,073 km` — value + unit, NBSP separated. */
    private val DISTANCE = Regex("""^([\d.,]+)\s*(km|m)$""", RegexOption.IGNORE_CASE)

    /**
     * android.title → metres.
     *
     * Null when the field held something else: it is overloaded and also
     * carries `Starting navigation…`, `Maps`, or an empty string. The empty
     * string is meaningful — it marks a maneuver completing before the next
     * one loads.
     */
    fun distanceMeters(title: String?): Int? {
        val m = DISTANCE.matchEntire(ascii(title ?: return null)) ?: return null
        val value = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "km" -> (value * 1000).toInt()
            else -> value.toInt()
        }
    }

    /** Display-ready distance, ASCII. Null unless the title really is a distance. */
    fun distanceText(title: String?): String? {
        val clean = ascii(title ?: return null)
        return if (DISTANCE.matches(clean)) clean else null
    }

    // ---- ETA ----------------------------------------------------------------

    /**
     * subText tail, e.g. `6 min · 3.5 km · 2:36 pm ETA` → `2:36 PM`.
     *
     * Parsed from the right, not by splitting into fixed positions: the
     * duration segment varies in structure (`10 d 2 hr` vs `4 min` vs `0 min`).
     * Uppercased because the corpus gives lowercase `pm` and the MVP spec
     * wants `7:25 PM`.
     */
    private val ETA = Regex("""(\d{1,2}:\d{2})\s*(am|pm)""", RegexOption.IGNORE_CASE)

    fun etaText(subText: String?): String? {
        val m = ETA.find(ascii(subText ?: return null)) ?: return null
        return "${m.groupValues[1]} ${m.groupValues[2].uppercase()}"
    }

    // ---- trip totals --------------------------------------------------------

    /**
     * subText is `duration · remaining · ETA`, e.g. `6 min · 3.5 km · 2:36 pm ETA`.
     *
     * Split on the middle dot rather than by position: the duration segment
     * varies in structure across the corpus (`10 d 2 hr`, `4 min`, `0 min`), so
     * fixed indexing into a token list is not safe. Three parts is the only
     * shape observed; anything else returns null rather than guessing.
     */
    private fun subTextParts(subText: String?): List<String>? {
        val parts = ascii(subText ?: return null)
            .split("·")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.size == 3) parts else null
    }

    /** Distance still to drive, e.g. `3.5 km`. Middle segment. */
    fun remainingDistance(subText: String?): String? = subTextParts(subText)?.get(1)

    /** Time still to drive, e.g. `6 min`. First segment. */
    fun remainingDuration(subText: String?): String? = subTextParts(subText)?.get(0)

    // ---- road name ----------------------------------------------------------

    /**
     * android.text → the road to display, or null when the text names no road.
     *
     * Three grammatical forms observed:
     *   `Nehru Nagar Main Rd`                     → as-is
     *   `towards NH 53`                           → strip the prefix
     *   `Agrasen Chowk onto Nehru Nagar Main Rd`  → keep what follows `onto`
     *
     * Returns null for pure verbs (`Turn left`) and compass headings
     * (`Head south`), which name nothing, and for the non-guidance states.
     */
    fun roadName(text: String?): String? {
        var t = ascii(text ?: return null)
        if (t.isEmpty()) return null
        if (ManeuverClassifier.isNonGuidanceText(t)) return null

        // `X onto Y` — the junction is context, the road is what you need.
        val onto = t.indexOf(" onto ", ignoreCase = true)
        if (onto >= 0) t = t.substring(onto + 6).trim()

        // `towards X` — the preposition costs 8 of ~18 characters.
        if (t.startsWith("towards ", ignoreCase = true)) t = t.substring(8).trim()

        // A bare verb or heading names no road.
        if (isVerbOnly(t)) return null

        return t.ifEmpty { null }
    }

    private fun isVerbOnly(t: String): Boolean {
        val l = t.lowercase()
        return l.startsWith("head ") ||
                l == "turn left" || l == "turn right" ||
                l == "keep right" || l == "keep left" ||
                l == "starting navigation"
    }

    // ---- display fitting ----------------------------------------------------

    /**
     * SYNC 1 advertises 40 characters per field but renders a proportional
     * font: 15 capital M's fill a line, digits and periods reach ~24. Measured
     * in the car. 18 is the safe budget for mixed text.
     */
    const val MAX_LINE = 18

    private val ABBREVIATIONS = listOf(
        " Road" to " Rd", " Street" to " St", " Avenue" to " Ave",
        " Main" to " Mn", " North" to " N", " South" to " S",
        " East" to " E", " West" to " W", " Nagar" to " Ngr",
    )

    /**
     * Squeezes a road name into [MAX_LINE]: abbreviate first, truncate only if
     * that is not enough. Ellipsis is deliberate — a driver seeing `Nehru Nag…`
     * knows it is cut, where a hard chop reads as a different road.
     */
    fun fitRoad(road: String?): String? {
        var s = road ?: return null
        if (s.length <= MAX_LINE) return s
        for ((long, short) in ABBREVIATIONS) s = s.replace(long, short, ignoreCase = true)
        if (s.length <= MAX_LINE) return s
        return s.take(MAX_LINE - 1).trimEnd() + "\u2026"
    }

    /** `7:25 PM` → `7:25P`, saving two characters on the top line. */
    fun compactEta(eta: String?): String? =
        eta?.replace(" AM", "A")?.replace(" PM", "P")
}