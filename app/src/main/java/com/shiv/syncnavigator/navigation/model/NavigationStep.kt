package com.shiv.syncnavigator.navigation.model

/**
 * PHASE 2 / M1 — the domain model the parser produces and DisplayManager consumes.
 *
 * No Android dependencies, deliberately: plain Kotlin so the parser can be
 * exercised on a desktop JVM against captured JSON.
 *
 * Every member is grounded in the 745-record corpus captured on a Motorola
 * Edge 50 Neo (Android 15) against Google Maps. Nothing here is anticipated.
 *
 * Sealed rather than one nullable-heavy class because the non-driving states
 * carry no payload at all — during `Rerouting...` there is no icon and subText
 * is empty, so there is genuinely no distance and no ETA. The sealed form makes
 * the `when` exhaustive at compile time instead of relying on null checks.
 */
sealed interface NavigationStep {

    /**
     * Capture time of the notification this came from.
     *
     * Present on every state because CaptureStore.latest is never cleared when
     * navigation ends, so downstream code must be able to spot a stale step.
     */
    val capturedAtMillis: Long

    /** Live guidance — the only state carrying anything displayable. */
    data class Navigating(
        override val capturedAtMillis: Long,

        /**
         * From the notification's large icon, never from [rawInstruction].
         * The corpus is unambiguous: `NH 53` appears under a LEFT arrow 17×
         * and a different arrow 7×; `Agrasen Rd` under both RIGHT and STRAIGHT.
         */
        val maneuver: Maneuver,

        /**
         * Road, junction or destination. Null when the notification carried
         * only a verb (`Turn left`) or a heading (`Head south`).
         * Three forms observed: `X`, `towards X`, `X onto Y`.
         */
        val roadName: String?,

        /**
         * Distance to [maneuver], normalised to metres. Source mixes units
         * (`40 m`, `900 m`, `2.5 km`) with a U+00A0 separator. Null when
         * android.title was empty — which marks a maneuver completing.
         */
        val distanceMeters: Int?,

        /** Display-ready distance, non-breaking space normalised out. */
        val distanceText: String?,

        /**
         * Arrival clock time, e.g. `7:25 PM`. Confirmed absolute wall-clock in
         * the source, not a remaining duration. Null while subText is empty.
         */
        val etaText: String?,

        /** Distance still to drive, e.g. `3.5 km`. From subText. */
        val tripDistance: String?,

        /** Time still to drive, e.g. `6 min`. From subText. */
        val tripDuration: String?,
        /** Unmodified android.text. Carried for in-car diagnostics via logcat. */
        val rawInstruction: String?,
    ) : NavigationStep

    /** Route accepted, first instruction not yet issued. */
    data class Starting(override val capturedAtMillis: Long) : NavigationStep

    /** Off-route; Maps recalculating. No icon, empty subText. */
    data class Rerouting(override val capturedAtMillis: Long) : NavigationStep

    /** Position lost. Observed as `Waiting for location...`. */
    data class AwaitingLocation(override val capturedAtMillis: Long) : NavigationStep

    /**
     * The system replaced the notification's contents. Observed as
     * `Sensitive notification content hidden` with appInfo reporting `android`.
     * Distinct from [NotNavigating] — navigation is still running underneath.
     */
    data class Redacted(override val capturedAtMillis: Long) : NavigationStep

    /** No active navigation — notification removed, or never present. */
    data class NotNavigating(override val capturedAtMillis: Long) : NavigationStep
}

/**
 * Turn indication, resolved from the large-icon bitmap.
 *
 * Members correspond to arrow bitmaps actually seen in the corpus. Mapping
 * SHA-1s onto them is M4's job and lives nowhere in this file.
 *
 * No roundabout, bear-left or U-turn: those routes were not driven. New arrows
 * must resolve to [UNKNOWN] rather than a plausible neighbour — a generic arrow
 * beside the right road name is recoverable by a driver, a confidently wrong
 * arrow at a junction is not.
 */
enum class Maneuver {
    /** 407×, alongside `Turn left` 175×. */
    TURN_LEFT,

    /** 213×, alongside `Turn right` 182×. Exact mirror of [TURN_LEFT]. */
    TURN_RIGHT,

    /** 64×, alongside every `Head <compass>` instruction. */
    STRAIGHT,

    /** 7×. The [STRAIGHT] arrow (0.993 pixel overlap) drawn above a junction. */
    CONTINUE_THROUGH_JUNCTION,

    /** 14×. Branches right over a vertical shaft; not a mirror of [TURN_RIGHT]. */
    KEEP_RIGHT,

    /** 9×, only beside a destination sign. Least certain identification. */
    KEEP_LEFT,
    FORK,

    /** 2×, while still 10 m out, immediately before removal. */
    ARRIVE,

    /** Arrow not in the known set. Always preferred over guessing. */
    UNKNOWN,
}