package com.example.data.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

object AppCheckDiagnostics {
    @Volatile
    var providerLabel: String = "Uninitialized"
        internal set

    @Volatile
    var detail: String = "Waiting for startup"
        internal set

    @Volatile
    var isHealthy: Boolean = false
        internal set

    fun summaryLine(): String = "$providerLabel · $detail"
}

/**
 * Awaits a valid Firebase App Check token before the first Gemini AI Scout request fires.
 * Prevents cold-start race conditions on reCAPTCHA Enterprise and Play Integrity where the
 * provider finishes initializing ~1-2s after Application.onCreate(), causing the very first
 * network call to reject with "Firebase App Check token is invalid" while immediate retries succeed.
 */
suspend fun awaitWarmAppCheckToken(
    forceRefresh: Boolean = false,
    timeoutMs: Long = 4_000L,
): Boolean {
    suspend fun fetchOnce(refresh: Boolean): Result<String> {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                try {
                    FirebaseAppCheck.getInstance()
                        .getAppCheckToken(refresh)
                        .addOnSuccessListener { token ->
                            val raw = token?.token.orEmpty()
                            if (cont.isActive) {
                                if (raw.isNotBlank()) {
                                    cont.resume(Result.success(raw))
                                } else {
                                    cont.resume(Result.failure(IllegalStateException("Empty App Check token")))
                                }
                            }
                        }
                        .addOnFailureListener { exc ->
                            if (cont.isActive) cont.resume(Result.failure(exc))
                        }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(Result.failure(e))
                }
            }
        } ?: Result.failure(IllegalStateException("App Check token warm-up timed out"))
    }

    if (AppCheckDiagnostics.providerLabel == "Unavailable") return false
    val first = fetchOnce(forceRefresh)
    if (first.isSuccess) {
        AppCheckDiagnostics.detail = "Token verified"
        AppCheckDiagnostics.isHealthy = true
        return true
    }

    // Cold-start retry: reCAPTCHA Enterprise / Play Integrity often becomes ready ~1s after launch
    Log.w(TAG, "Initial App Check warm-up failed (${first.exceptionOrNull()?.localizedMessage}); retrying once.")
    delay(1_000L)
    val second = fetchOnce(true)
    if (second.isSuccess) {
        AppCheckDiagnostics.detail = "Token verified (after warm-up)"
        AppCheckDiagnostics.isHealthy = true
        return true
    }

    AppCheckDiagnostics.isHealthy = false
    AppCheckDiagnostics.detail = "Token verification failed"
    Log.w(TAG, "App Check warm-up retry failed: ${second.exceptionOrNull()?.localizedMessage}")
    return false
}

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty() && FirebaseApp.initializeApp(context) == null) {
            AppCheckDiagnostics.providerLabel = "Unavailable"
            AppCheckDiagnostics.detail = "Cloud dialogue is not configured for this build."
            AppCheckDiagnostics.isHealthy = false
            return
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = FirebaseApp.getInstance().persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                // Must use synchronous commit() before DebugAppCheckProviderFactory initializes
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "App Check debug secret configured successfully.")
                AppCheckDiagnostics.detail = "Debug token injected"
            } else {
                Log.w(TAG, "No debug token found in Intent extra.")
                AppCheckDiagnostics.detail = "Debug provider (no launch token)"
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            AppCheckDiagnostics.providerLabel = "Debug App Check"
            AppCheckDiagnostics.isHealthy = false
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val resId = context.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                context.packageName
            )
            val siteKey = if (resId != 0) context.getString(resId).trim() else ""
            if (siteKey.isNotBlank()) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                )
                AppCheckDiagnostics.providerLabel = "reCAPTCHA Enterprise"
                AppCheckDiagnostics.detail = "Release provider active"
                AppCheckDiagnostics.isHealthy = false
                Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
            } else {
                // Google Play Store release builds use Play Integrity automatically without needing a manual key.
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                )
                AppCheckDiagnostics.providerLabel = "Play Integrity"
                AppCheckDiagnostics.detail = "Release provider active"
                AppCheckDiagnostics.isHealthy = false
                Log.d(TAG, "PlayIntegrityAppCheckProviderFactory installed for release build.")
            }
        }
    } catch (e: Exception) {
        AppCheckDiagnostics.providerLabel = "App Check Error"
        AppCheckDiagnostics.detail = e.localizedMessage?.take(80) ?: e.javaClass.simpleName
        AppCheckDiagnostics.isHealthy = false
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
