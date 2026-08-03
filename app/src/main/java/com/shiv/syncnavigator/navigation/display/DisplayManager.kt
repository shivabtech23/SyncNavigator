package com.shiv.syncnavigator.navigation.display

import com.shiv.syncnavigator.navigation.model.Maneuver
import com.shiv.syncnavigator.navigation.model.NavigationStep
import com.shiv.syncnavigator.navigation.parser.FieldExtractors

/**
 * PHASE 3 — formats a [NavigationStep] into the two lines SYNC 1 can show.
 *
 * Measured constraints, not guesses: the head unit advertises mainField1..4 at
 * 40 characters, but renders a proportional font and only shows two lines at a
 * time with a scrollbar for the rest. A driver will not scroll. So everything
 * that matters goes in two lines of ~18 characters, and fields 3-4 carry the
 * untruncated road for anyone who does scroll.
 *
 * graphicSupported=false, so the arrow is a word, not a glyph.
 */
object DisplayManager {

    data class Lines(
        val line1: String,
        val line2: String,
        val line3: String? = null,
        val line4: String? = null,
    )

    /** Short enough to leave room for distance and ETA on the same line. */
    /**
     * ASCII arrows only — CID1SET has no arrow glyphs, so `→` would render as
     * a box or nothing. Direction is carried by which side the chevrons sit on.
     *
     * Kept short because line 1 also holds the distance and the whole line has
     * to fit ~18 characters: `RIGHT >> 200 m` is 14, `<< LEFT 10 m` is 12.
     */
    private fun label(m: Maneuver): String = when (m) {
        Maneuver.TURN_LEFT -> "<< LEFT"
        Maneuver.TURN_RIGHT -> "RIGHT >>"
        Maneuver.STRAIGHT -> "^ STRAIGHT"
        Maneuver.CONTINUE_THROUGH_JUNCTION -> "^ CONTINUE"
        Maneuver.KEEP_RIGHT -> "KEEP R >"
        Maneuver.KEEP_LEFT -> "< KEEP L"
        Maneuver.FORK -> "TAKE EXIT"
        Maneuver.ARRIVE -> "ARRIVING"
        Maneuver.UNKNOWN -> "CONTINUE"
    }

    /**
     * Line 1 is the safety-critical line and the only one guaranteed visible,
     * so maneuver and distance go there together. ETA is appended only if it
     * fits — it is the least urgent of the three.
     */
    /**
     * The safety-critical line and the only one guaranteed visible without
     * scrolling, so it carries the two things a driver needs at a junction:
     * which way, and how far. ETA moved to line 2 to make room for full words.
     */
    private fun buildLine1(step: NavigationStep.Navigating): String {
        val base = label(step.maneuver)
        val dist = step.distanceText ?: return base
        val full = "$base $dist"
        // A long instruction plus a long distance can overflow; distance wins,
        // because "50 m" with no verb is still actionable and the reverse is not.
        return if (full.length <= FieldExtractors.MAX_LINE) full else dist
    }

    /**
     * Road first, ETA otherwise.
     *
     * At a merge, flyover or exit Maps supplies a target — `towards NH 53`,
     * `Nehru Nagar Main Rd` — and that is worth the whole line until the
     * junction is behind you. Once Maps stops naming a road, the line falls
     * back to ETA, then to trip totals if even that is missing.
     */
    private fun buildLine2(step: NavigationStep.Navigating, road: String?): String {
        FieldExtractors.fitRoad(road)?.let { return it }

        step.etaText?.let { return "ETA $it" }

        val dist = step.tripDistance
        val dur = step.tripDuration
        if (dist != null || dur != null) {
            return listOfNotNull(dist, dur).joinToString("  ")
        }

        return ""
    }

    fun format(step: NavigationStep): Lines = when (step) {
        is NavigationStep.Navigating -> {
            val road = step.roadName
            Lines(
                line1 = buildLine1(step),
                line2 = buildLine2(step, road),
                // Untruncated road, for anyone who scrolls.
                line3 = if (road != null && road.length > FieldExtractors.MAX_LINE) road else null,
                line4 = listOfNotNull(step.tripDistance, step.tripDuration)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("  "),
            )
        }

        is NavigationStep.Rerouting -> Lines("REROUTING", "")
        is NavigationStep.AwaitingLocation -> Lines("NO GPS SIGNAL", "")
        is NavigationStep.Starting -> Lines("STARTING", "")
        is NavigationStep.Redacted -> Lines("GUIDANCE PAUSED", "")
        is NavigationStep.NotNavigating -> Lines("SYNC Navigator", "No navigation")
    }

    /**
     * Whether a new step should replace what is on screen.
     *
     * Maps reposts roughly twice a second and each Show costs 130-900 ms over
     * Bluetooth (measured). Resending identical text would saturate the link
     * for nothing, so only real changes go out.
     *
     * The hold rule matters more: during Rerouting and Redacted we keep the
     * previous guidance rather than blanking. A briefly stale "300 m, Nehru
     * Nagar Rd" is far better for a driver than a display that flickers empty
     * and comes back. Redaction bursts lasted six seconds in the corpus.
     */
    fun shouldHold(step: NavigationStep): Boolean =
        step is NavigationStep.Rerouting || step is NavigationStep.Redacted
}