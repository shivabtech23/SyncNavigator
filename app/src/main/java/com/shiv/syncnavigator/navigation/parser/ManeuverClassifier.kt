package com.shiv.syncnavigator.navigation.parser

import com.shiv.syncnavigator.navigation.model.Maneuver

/**
 * PHASE 2 / M4 — resolves a notification into a [Maneuver].
 *
 * No Android dependencies: pure functions over strings, testable on a JVM.
 *
 * The corpus settled how this has to work. Google Maps puts the *target* in
 * android.text (`Nehru Nagar Main Rd`, `towards NH 53`) and the *turn* only in
 * the large-icon bitmap. `NH 53` appears under a LEFT arrow 17 times and a
 * different arrow 7 times; `Agrasen Rd` under both RIGHT and STRAIGHT. Text
 * alone cannot tell you which way to turn.
 *
 * Hence the ladder in [classify]: icon first, verb second, UNKNOWN last.
 */
object ManeuverClassifier {

    /**
     * SHA-1 of the raw arrow pixels → maneuver.
     *
     * Keyed on the hash because the icon arrives as TYPE_BITMAP, so
     * resourceName is always null for it — there is no `ic_maneuver_turn_left`
     * to match on. Hashes proved stable across sessions 11 hours apart.
     *
     * If Maps ever restyles these, every entry breaks at once and everything
     * falls through to [UNKNOWN] — degraded, not wrong. That is the intended
     * failure mode.
     */
    private val ARROWS: Map<String, Maneuver> = mapOf(
        "b652f64900408f97a01b718bb0f801000bd68468" to Maneuver.TURN_LEFT,
        "749636914e07012e52d275e02d1a05fc6d5a780a" to Maneuver.TURN_RIGHT,
        "3b648c1aaf70439def1469441db035e98e4ba64c" to Maneuver.STRAIGHT,
        "d66f55f2fe7579782cf8810e65f84e94a4d29ea0" to Maneuver.CONTINUE_THROUGH_JUNCTION,
        "b9206cb21374dcd76901612c648df69174093a87" to Maneuver.KEEP_RIGHT,
        "009bbd50327393567e9897a8f2332f2e7351e82b" to Maneuver.FORK,
        "275610de79a3751219ffc05461f2e6a829c10e9d" to Maneuver.ARRIVE,
    )

    /**
     * @param iconSha1 SHA-1 of the notification's large icon, or null when the
     *   notification carried no icon — true of every Rerouting, GPS-loss and
     *   redacted record in the corpus.
     * @param text raw android.text, used only as a fallback.
     */
    fun classify(iconSha1: String?, text: String?): Maneuver {
        // 1. Known arrow. The only reliable signal.
        ARROWS[iconSha1?.lowercase()]?.let { return it }

        // 2. Unknown or absent arrow: fall back to a verb, if the text has one.
        //    Covers arrows this corpus never saw — roundabouts, bear-left and
        //    U-turns were not driven and certainly exist in the wild.
        classifyByVerb(text)?.let { return it }

        // 3. Neither. A generic arrow beside the right road name is recoverable
        //    by a driver; a confidently wrong arrow at a junction is not.
        return Maneuver.UNKNOWN
    }

    /**
     * Verb fallback. Deliberately narrow — it matches only the leading verb
     * phrases actually observed, and will not try to infer a turn from a road
     * name. `Agrasen Rd` tells you nothing about direction and must not pretend
     * otherwise.
     */
    private fun classifyByVerb(text: String?): Maneuver? {
        val t = text?.trim()?.lowercase() ?: return null
        return when {
            t.startsWith("turn left") -> Maneuver.TURN_LEFT
            t.startsWith("turn right") -> Maneuver.TURN_RIGHT
            t.startsWith("keep right") -> Maneuver.KEEP_RIGHT
            t.startsWith("head ") -> Maneuver.STRAIGHT
            else -> null
        }
    }

    /** True for the states that carry no maneuver at all. */
    fun isNonGuidanceText(text: String?): Boolean {
        val t = text?.trim() ?: return false
        return t == "Rerouting..." ||
                t == "Waiting for location..." ||
                t == "Sensitive notification content hidden"
    }
}