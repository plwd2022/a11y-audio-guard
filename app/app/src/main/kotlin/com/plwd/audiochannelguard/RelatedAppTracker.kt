package com.plwd.audiochannelguard

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

class RelatedAppTracker(
    private val context: Context,
    private val callbackHandler: Handler,
    private val onHintChanged: () -> Unit,
) {

    private data class Candidate(
        val packageName: String,
        val appLabel: String,
        val score: Int,
        val matchedAtMs: Long,
        val behaviorSummary: String,
        val hasForegroundEvidence: Boolean,
    )

    private data class PackageMetadata(
        val appLabel: String,
        val isSystem: Boolean,
        val hasModifyAudioSettings: Boolean,
    )

    private data class ActivityState(
        val packageName: String,
        val appLabel: String,
        val lastResumedAtMs: Long = 0L,
        val lastBackgroundAtMs: Long = 0L,
    )

    companion object {
        private const val ACTIVE_WINDOW_MS = 20_000L
        private const val RETENTION_WINDOW_MS = 60_000L
        private const val DISPLAY_SCORE_THRESHOLD = 6
        private const val DISPLAY_FOREGROUND_SCORE_THRESHOLD = 5
        private const val DISPLAY_SCORE_MARGIN = 2
        private const val DISPLAY_FOREGROUND_SCORE_MARGIN = 1
    }

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val packageMetadataCache = mutableMapOf<String, PackageMetadata>()
    private var lastIncidentAtMs = 0L
    private var activeUntilMs = 0L
    private var retainUntilMs = 0L
    private var currentHint: RelatedAppHint? = restoreHint()

    private val timeoutRunnable = Runnable {
        val now = System.currentTimeMillis()
        if (now >= retainUntilMs) {
            clearAll()
            return@Runnable
        }
        rebuildHint(now)
        scheduleTimeout(now)
    }

    init {
        currentHint?.let {
            lastIncidentAtMs = it.happenedAtMs
            activeUntilMs = it.activeUntilMs
            retainUntilMs = it.expiresAtMs
        }
        val now = System.currentTimeMillis()
        if (retainUntilMs != 0L && now >= retainUntilMs) {
            clearAll(notify = false)
        } else {
            scheduleTimeout(now)
        }
    }

    fun getHint(): RelatedAppHint? = currentHint

    fun refresh() {
        val now = System.currentTimeMillis()
        if (retainUntilMs != 0L && now >= retainUntilMs) {
            clearAll()
            return
        }

        if (
            lastIncidentAtMs != 0L &&
            currentHint?.kind == RelatedAppHintKind.PERMISSION_REQUIRED &&
            UsageAccessPermissionResolver.isGranted(context)
        ) {
            val nextHint = resolveIncidentHint(lastIncidentAtMs, now)
            if (nextHint == null) {
                clearAll()
                return
            }
            updateHint(nextHint)
            scheduleTimeout(now)
            return
        }

        rebuildHint(now)
        scheduleTimeout(now)
    }

    fun recordInterference(observedAtMs: Long = System.currentTimeMillis()) {
        lastIncidentAtMs = observedAtMs
        activeUntilMs = observedAtMs + ACTIVE_WINDOW_MS
        retainUntilMs = observedAtMs + RETENTION_WINDOW_MS

        val nextHint = resolveIncidentHint(observedAtMs, observedAtMs)
        updateHint(nextHint)
        scheduleTimeout(observedAtMs)
    }

    fun stop() {
        callbackHandler.removeCallbacks(timeoutRunnable)
        AudioGuardApp.setRelatedAppHint(context, currentHint)
    }

    private fun restoreHint(): RelatedAppHint? {
        return AudioGuardApp.getRelatedAppHint(context)
    }

    private fun resolveIncidentHint(observedAtMs: Long, now: Long): RelatedAppHint? {
        val active = now < activeUntilMs
        if (!UsageAccessPermissionResolver.isGranted(context)) {
            return RelatedAppHint(
                kind = RelatedAppHintKind.PERMISSION_REQUIRED,
                happenedAtMs = observedAtMs,
                active = active,
                activeUntilMs = activeUntilMs,
                expiresAtMs = retainUntilMs,
            )
        }

        val bestCandidate = resolveBestCandidate(observedAtMs)
        if (bestCandidate == null) {
            return RelatedAppHint(
                kind = RelatedAppHintKind.EVENT_ONLY,
                happenedAtMs = observedAtMs,
                active = active,
                activeUntilMs = activeUntilMs,
                expiresAtMs = retainUntilMs,
            )
        }
        return RelatedAppHint(
            kind = RelatedAppHintKind.APP,
            packageName = bestCandidate.packageName,
            appLabel = bestCandidate.appLabel,
            happenedAtMs = bestCandidate.matchedAtMs,
            behaviorSummary = bestCandidate.behaviorSummary,
            active = active,
            activeUntilMs = activeUntilMs,
            expiresAtMs = retainUntilMs,
        )
    }

    private fun resolveBestCandidate(observedAtMs: Long): Candidate? {
        val lookbackWindowMs = RelatedAppEvidenceResolver.currentForegroundLookbackWindowMs()
        val usageEvents = usageStatsManager.queryEvents(
            maxOf(0L, observedAtMs - lookbackWindowMs),
            observedAtMs
        )
        val ignoredPackages = resolveIgnoredPackages()
        val activityStates = linkedMapOf<String, ActivityState>()
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val packageName = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (shouldIgnorePackage(packageName, ignoredPackages)) {
                        continue
                    }
                    val metadata = resolvePackageMetadata(packageName) ?: continue
                    if (metadata.isSystem || !metadata.hasModifyAudioSettings) {
                        continue
                    }
                    val current = activityStates[packageName]
                    activityStates[packageName] = ActivityState(
                        packageName = packageName,
                        appLabel = metadata.appLabel,
                        lastResumedAtMs = event.timeStamp,
                        lastBackgroundAtMs = current?.lastBackgroundAtMs ?: 0L,
                    )
                }

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val current = activityStates[packageName] ?: continue
                    activityStates[packageName] = current.copy(
                        lastBackgroundAtMs = maxOf(current.lastBackgroundAtMs, event.timeStamp)
                    )
                }
            }
        }

        val candidates = activityStates.values
            .mapNotNull { buildCandidate(it, observedAtMs) }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.matchedAtMs })
        val bestCandidate = candidates.firstOrNull() ?: return null
        val secondCandidate = candidates.getOrNull(1)
        if (!shouldDisplayCandidate(bestCandidate, secondCandidate)) {
            return null
        }
        return bestCandidate
    }

    private fun buildCandidate(state: ActivityState, observedAtMs: Long): Candidate? {
        if (state.lastResumedAtMs == 0L) {
            return null
        }

        val evidence = RelatedAppEvidenceResolver.resolve(
            RelatedAppEvidenceInput(
                resumeDeltaMs = observedAtMs - state.lastResumedAtMs,
                isForegroundAtIncident = state.lastResumedAtMs > state.lastBackgroundAtMs,
            )
        ) ?: return null

        val hasForegroundEvidence = state.lastResumedAtMs > state.lastBackgroundAtMs
        return Candidate(
            packageName = state.packageName,
            appLabel = state.appLabel,
            score = evidence.score,
            matchedAtMs = if (hasForegroundEvidence) observedAtMs else state.lastResumedAtMs,
            behaviorSummary = evidence.behaviorSummary,
            hasForegroundEvidence = hasForegroundEvidence,
        )
    }

    private fun shouldDisplayCandidate(best: Candidate, second: Candidate?): Boolean {
        val threshold = if (best.hasForegroundEvidence) {
            DISPLAY_FOREGROUND_SCORE_THRESHOLD
        } else {
            DISPLAY_SCORE_THRESHOLD
        }
        if (best.score < threshold) {
            return false
        }
        if (second == null) {
            return true
        }
        val margin = if (best.hasForegroundEvidence) {
            DISPLAY_FOREGROUND_SCORE_MARGIN
        } else {
            DISPLAY_SCORE_MARGIN
        }
        return best.score >= second.score + margin
    }

    private fun rebuildHint(now: Long) {
        val hint = currentHint ?: return
        val active = now < activeUntilMs
        val nextHint = if (hint.active == active) {
            hint
        } else {
            hint.copy(active = active)
        }
        updateHint(nextHint)
    }

    private fun scheduleTimeout(now: Long) {
        callbackHandler.removeCallbacks(timeoutRunnable)

        if (retainUntilMs == 0L || now >= retainUntilMs || currentHint == null) {
            return
        }

        val nextTimeoutAt = if (now < activeUntilMs) {
            activeUntilMs
        } else {
            retainUntilMs
        }
        callbackHandler.postDelayed(timeoutRunnable, maxOf(1L, nextTimeoutAt - now))
    }

    private fun resolvePackageMetadata(packageName: String): PackageMetadata? {
        packageMetadataCache[packageName]?.let { return it }

        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val metadata = PackageMetadata(
                appLabel =
                    packageManager.getApplicationLabel(appInfo).toString()
                        .takeIf { it.isNotBlank() }
                        ?: packageName,
                isSystem =
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0,
                hasModifyAudioSettings =
                    packageInfo.requestedPermissions.orEmpty()
                        .contains(Manifest.permission.MODIFY_AUDIO_SETTINGS),
            )
            packageMetadataCache[packageName] = metadata
            metadata
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveIgnoredPackages(): Set<String> {
        return buildSet {
            add(context.packageName)
            addAll(resolveHomePackages())
            resolveDefaultInputMethodPackage()?.let(::add)
            addAll(resolveEnabledAccessibilityPackages())
        }
    }

    private fun resolveHomePackages(): Set<String> {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            context.packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName?.takeIf(String::isNotBlank) }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun resolveDefaultInputMethodPackage(): String? {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
    }

    private fun resolveEnabledAccessibilityPackages(): Set<String> {
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return emptySet()
        return try {
            accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
                .mapNotNull { it.resolveInfo?.serviceInfo?.packageName?.takeIf(String::isNotBlank) }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun shouldIgnorePackage(packageName: String, ignoredPackages: Set<String>): Boolean {
        return packageName.isBlank() || packageName in ignoredPackages
    }

    private fun updateHint(nextHint: RelatedAppHint?) {
        if (currentHint == nextHint) {
            AudioGuardApp.setRelatedAppHint(context, nextHint)
            return
        }

        currentHint = nextHint
        AudioGuardApp.setRelatedAppHint(context, nextHint)
        onHintChanged()
    }

    private fun clearAll(notify: Boolean = true) {
        callbackHandler.removeCallbacks(timeoutRunnable)
        lastIncidentAtMs = 0L
        activeUntilMs = 0L
        retainUntilMs = 0L
        if (currentHint != null) {
            currentHint = null
            AudioGuardApp.setRelatedAppHint(context, null)
            if (notify) {
                onHintChanged()
            }
            return
        }
        AudioGuardApp.setRelatedAppHint(context, null)
    }
}
