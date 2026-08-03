package com.shiv.syncnavigator.navigation.parser

import com.shiv.syncnavigator.navigation.model.NavigationStep
import com.shiv.syncnavigator.navigation.notification.CaptureStore
import com.shiv.syncnavigator.navigation.notification.NotificationCapture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * PHASE 2 / M7 — turns captures into [NavigationStep]s.
 *
 * Option A, as agreed: subscribes to CaptureStore.latest and nothing else.
 * Phase 1 is untouched — no hook in NotificationLoggerService.
 *
 * Two consequences of that choice, both known and accepted:
 *  - CaptureStore.latest is never cleared when navigation ends, so the last
 *    step persists after Maps stops. [isStale] exists for that.
 *  - The debug screen's Clear button nulls latest, which surfaces here as
 *    NotNavigating.
 */
object NavigationStepProvider {

    /** Live steps, one per captured notification. */
    val steps: Flow<NavigationStep> =
        CaptureStore.latest.map { capture ->
            if (capture == null) NavigationStep.NotNavigating(System.currentTimeMillis())
            else parse(capture)
        }

    /**
     * Pure function: capture in, step out. Testable without a device, which is
     * the whole reason the parser never touches Android APIs.
     */
    fun parse(c: NotificationCapture): NavigationStep {
        val extras = c.extras.associate { it.key to it.value }
        val title = extras["android.title"]
        val text = extras["android.text"]
        val subText = extras["android.subText"]
        val at = c.capturedAtMillis

        // Non-guidance states first — they carry no icon and an empty subText,
        // so trying to extract fields from them would produce nonsense.
        when (text?.trim()) {
            "Rerouting..." -> return NavigationStep.Rerouting(at)
            "Waiting for location..." -> return NavigationStep.AwaitingLocation(at)
            "Sensitive notification content hidden" -> return NavigationStep.Redacted(at)
        }

        // android.title is overloaded: distance, or a status string.
        if (title?.trim()?.startsWith("Starting navigation") == true) {
            return NavigationStep.Starting(at)
        }

        val iconSha1 = c.largeIcon?.image?.sha1

        // No icon and no usable text means there is nothing to show.
        if (iconSha1 == null && text.isNullOrBlank()) {
            return NavigationStep.NotNavigating(at)
        }

        return NavigationStep.Navigating(
            capturedAtMillis = at,
            maneuver = ManeuverClassifier.classify(iconSha1, text),
            roadName = FieldExtractors.roadName(text),
            distanceMeters = FieldExtractors.distanceMeters(title),
            distanceText = FieldExtractors.distanceText(title),
            etaText = FieldExtractors.etaText(subText),
            tripDistance = FieldExtractors.remainingDistance(subText),
            tripDuration = FieldExtractors.remainingDuration(subText),
            rawInstruction = text,
        )
    }

    /**
     * True when a step is old enough to distrust.
     *
     * Maps reposts roughly twice a second while navigating, so anything older
     * than a few seconds means navigation stopped and CaptureStore simply never
     * found out. Ten seconds is generous enough to survive a redaction burst
     * (six seconds, observed) without leaving a dead step on the SYNC display.
     */
    fun isStale(step: NavigationStep, nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - step.capturedAtMillis > STALE_AFTER_MILLIS

    const val STALE_AFTER_MILLIS = 10_000L
}