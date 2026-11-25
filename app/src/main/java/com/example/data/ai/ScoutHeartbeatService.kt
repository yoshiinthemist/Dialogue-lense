package com.example.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

enum class ScoutHeartbeatStatus(val label: String) {
    IDLE("Idle"),
    HEALTHY("Healthy"),
    DEGRADED("Degraded"),
    RECONNECTING("Reconnecting"),
    QUOTA_EXHAUSTED("Credits Depleted"),
    PAUSED_ZERO_CLOUD("Zero-Cloud"),
}

data class ScoutHeartbeatSnapshot(
    val status: ScoutHeartbeatStatus = ScoutHeartbeatStatus.IDLE,
    val modelName: String = "gemini-3.8-flash",
    val consecutiveFailures: Int = 0,
    val retryAttempt: Int = 0,
    val maxRetries: Int = 3,
    val nextRetryDelayMs: Long = 0L,
    val lastSuccessEpochMs: Long = 0L,
    val lastFailureEpochMs: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val lastErrorSummary: String = "",
    val totalSuccessfulCalls: Int = 0,
    val totalRecoveries: Int = 0,
) {
    private fun shortModelLabel(): String = when {
        modelName.contains("lite", ignoreCase = true) -> "Flash-Lite"
        modelName.contains("3.8", ignoreCase = true) -> "3.8 Flash"
        else -> "AI Scout"
    }

    fun summaryBadge(): String = when (status) {
        ScoutHeartbeatStatus.IDLE -> "Heartbeat idle"
        ScoutHeartbeatStatus.HEALTHY -> if (lastLatencyMs > 0L) "${shortModelLabel()} ${lastLatencyMs}ms" else "${shortModelLabel()} healthy"
        ScoutHeartbeatStatus.DEGRADED -> "Retry $retryAttempt/$maxRetries (${(nextRetryDelayMs / 1000.0).let { String.format(java.util.Locale.US, "%.1f", it) }}s)"
        ScoutHeartbeatStatus.RECONNECTING -> "Reconnecting ${shortModelLabel()} ($retryAttempt/$maxRetries)"
        ScoutHeartbeatStatus.QUOTA_EXHAUSTED -> "Credits depleted"
        ScoutHeartbeatStatus.PAUSED_ZERO_CLOUD -> "Zero-Cloud (local)"
    }
}

/**
 * Monitors the active Gemini Scout stream connection and executes stream/chunk requests
 * with exponential backoff retries on partial failures or transient dropouts during playback.
 */
class ScoutHeartbeatService(
    private val probeFlashLite: (suspend () -> Result<Long>)? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val sleepFn: suspend (Long) -> Unit = { delay(it) },
    private val baseDelayMs: Long = 1_200L,
    private val maxDelayMs: Long = 16_000L,
) {
    private val _snapshot = MutableStateFlow(ScoutHeartbeatSnapshot())
    val snapshot: StateFlow<ScoutHeartbeatSnapshot> = _snapshot.asStateFlow()

    private var monitorJob: Job? = null

    fun resetForNewStream(modelName: String = "gemini-3.8-flash") {
        _snapshot.value = ScoutHeartbeatSnapshot(
            status = ScoutHeartbeatStatus.IDLE,
            modelName = modelName,
        )
    }

    fun setZeroCloudPaused(paused: Boolean) {
        _snapshot.update { cur ->
            if (paused) {
                cur.copy(
                    status = ScoutHeartbeatStatus.PAUSED_ZERO_CLOUD,
                    nextRetryDelayMs = 0L,
                    retryAttempt = 0,
                )
            } else if (cur.status == ScoutHeartbeatStatus.PAUSED_ZERO_CLOUD) {
                cur.copy(status = ScoutHeartbeatStatus.IDLE)
            } else {
                cur
            }
        }
    }

    fun recordSuccess(latencyMs: Long = 0L, modelName: String = _snapshot.value.modelName) {
        val now = clockMs()
        _snapshot.update { cur ->
            val recovered = cur.consecutiveFailures > 0 ||
                cur.status == ScoutHeartbeatStatus.DEGRADED ||
                cur.status == ScoutHeartbeatStatus.RECONNECTING
            cur.copy(
                status = ScoutHeartbeatStatus.HEALTHY,
                modelName = modelName,
                consecutiveFailures = 0,
                retryAttempt = 0,
                nextRetryDelayMs = 0L,
                lastSuccessEpochMs = now,
                lastLatencyMs = latencyMs.coerceAtLeast(1L),
                lastErrorSummary = "",
                totalSuccessfulCalls = cur.totalSuccessfulCalls + 1,
                totalRecoveries = cur.totalRecoveries + if (recovered) 1 else 0,
            )
        }
    }

    fun recordFailure(
        error: Throwable?,
        retryAttempt: Int = 0,
        maxRetries: Int = 3,
        nextDelayMs: Long = 0L,
    ) {
        val now = clockMs()
        val isQuota = TranslationAiService.isQuotaOrCreditExhaustedError(error)
        val summary = error?.localizedMessage?.trim().orEmpty()
            .ifBlank { error?.javaClass?.simpleName.orEmpty() }
            .take(120)
        _snapshot.update { cur ->
            val nextConsecutive = cur.consecutiveFailures + 1
            cur.copy(
                status = when {
                    isQuota -> ScoutHeartbeatStatus.QUOTA_EXHAUSTED
                    retryAttempt > 0 && nextDelayMs > 0L -> ScoutHeartbeatStatus.RECONNECTING
                    else -> ScoutHeartbeatStatus.DEGRADED
                },
                consecutiveFailures = nextConsecutive,
                retryAttempt = retryAttempt,
                maxRetries = maxRetries,
                nextRetryDelayMs = nextDelayMs,
                lastFailureEpochMs = now,
                lastErrorSummary = summary,
            )
        }
    }

    /**
     * Runs a Flash-Lite stream operation with automatic exponential backoff retries
     * (`baseDelayMs * 2^(attempt-1)` capped at `maxDelayMs`), stopping immediately on quota exhaustion
     * or coroutine cancellation.
     */
    suspend fun <T> executeWithBackoffRetry(
        modelName: String = "gemini-3.5-flash-lite",
        maxRetries: Int = 3,
        onWarmToken: (suspend (attempt: Int) -> Unit)? = null,
        onRetryScheduled: (suspend (attempt: Int, maxRetries: Int, delayMs: Long, error: Throwable?) -> Unit)? = null,
        block: suspend () -> Result<T>,
    ): Result<T> {
        var attempt = 0
        var lastResult: Result<T>
        while (true) {
            val startedMs = clockMs()
            lastResult = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

            if (lastResult.isSuccess) {
                val elapsed = (clockMs() - startedMs).coerceAtLeast(1L)
                recordSuccess(latencyMs = elapsed, modelName = modelName)
                return lastResult
            }

            val err = lastResult.exceptionOrNull()
            if (err is CancellationException) throw err
            if (TranslationAiService.isQuotaOrCreditExhaustedError(err)) {
                recordFailure(err, retryAttempt = attempt, maxRetries = maxRetries, nextDelayMs = 0L)
                return lastResult
            }

            if (attempt >= maxRetries) {
                recordFailure(err, retryAttempt = attempt, maxRetries = maxRetries, nextDelayMs = 0L)
                return lastResult
            }

            attempt++
            val backoffMs = computeBackoffDelayMs(
                attempt = attempt,
                baseDelayMs = baseDelayMs,
                maxDelayMs = maxDelayMs,
                jitterMs = if (baseDelayMs >= 500L) Random.nextLong(0L, 260L) else 0L,
            )
            recordFailure(
                error = err,
                retryAttempt = attempt,
                maxRetries = maxRetries,
                nextDelayMs = backoffMs,
            )
            onRetryScheduled?.invoke(attempt, maxRetries, backoffMs, err)
            sleepFn(backoffMs)

            if (TranslationAiService.isAppCheckTransientError(err)) {
                onWarmToken?.invoke(attempt)
            }
        }
    }

    /**
     * Watches active playback and automatically runs a lightweight Flash-Lite connectivity probe
     * with exponential backoff whenever the stream previously suffered a dropout/degradation.
     * Consumes zero extra tokens when healthy or paused.
     */
    fun startPlaybackMonitor(
        scope: CoroutineScope,
        shouldMonitor: () -> Boolean,
        onConnectionRecovered: () -> Unit,
    ) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var monitorAttempt = 0
            while (isActive) {
                sleepFn(5_000L)
                if (!shouldMonitor()) {
                    monitorAttempt = 0
                    continue
                }
                val snap = _snapshot.value
                if (snap.status == ScoutHeartbeatStatus.QUOTA_EXHAUSTED ||
                    snap.status == ScoutHeartbeatStatus.PAUSED_ZERO_CLOUD
                ) {
                    continue
                }
                // Only probe over network when degraded/reconnecting so healthy streams stay at $0 extra cost
                if (snap.status == ScoutHeartbeatStatus.DEGRADED ||
                    snap.status == ScoutHeartbeatStatus.RECONNECTING
                ) {
                    val probe = probeFlashLite ?: continue
                    val probeRes = probe()
                    if (probeRes.isSuccess) {
                        monitorAttempt = 0
                        recordSuccess(latencyMs = probeRes.getOrDefault(1L))
                        onConnectionRecovered()
                    } else {
                        val err = probeRes.exceptionOrNull()
                        if (TranslationAiService.isQuotaOrCreditExhaustedError(err)) {
                            recordFailure(err)
                        } else {
                            monitorAttempt = (monitorAttempt + 1).coerceAtMost(5)
                            val delayMs = computeBackoffDelayMs(
                                attempt = monitorAttempt,
                                baseDelayMs = 2_500L,
                                maxDelayMs = 30_000L,
                                jitterMs = 0L,
                            )
                            recordFailure(err, retryAttempt = monitorAttempt, maxRetries = 5, nextDelayMs = delayMs)
                            sleepFn(delayMs)
                        }
                    }
                }
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    companion object {
        fun computeBackoffDelayMs(
            attempt: Int,
            baseDelayMs: Long = 1_200L,
            maxDelayMs: Long = 16_000L,
            jitterMs: Long = 0L,
        ): Long {
            val safeAttempt = attempt.coerceIn(1, 8)
            val exp = (baseDelayMs.toDouble() * 2.0.pow((safeAttempt - 1).toDouble())).toLong()
            return min(maxDelayMs, exp + jitterMs).coerceAtLeast(1L)
        }
    }
}
