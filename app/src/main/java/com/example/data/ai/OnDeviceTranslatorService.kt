package com.example.data.ai

import android.util.Log
import com.example.data.model.DialogueEvent
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "OnDeviceTranslator"

/**
 * 100% credit-free, offline ML Kit On-Device Translation service.
 * Used as the zero-quota default for cross-language subtitle translation when Quota-Saver or
 * Zero-Cloud mode is active, or when cloud Gemini credits are exhausted.
 */
class OnDeviceTranslatorService {

    fun languageCodeFor(friendlyName: String): String? {
        return when (friendlyName.trim().lowercase(Locale.US)) {
            "english", "en" -> TranslateLanguage.ENGLISH
            "spanish", "es" -> TranslateLanguage.SPANISH
            "french", "fr" -> TranslateLanguage.FRENCH
            "german", "de" -> TranslateLanguage.GERMAN
            "japanese", "ja" -> TranslateLanguage.JAPANESE
            "korean", "ko" -> TranslateLanguage.KOREAN
            "mandarin", "chinese", "zh" -> TranslateLanguage.CHINESE
            "italian", "it" -> TranslateLanguage.ITALIAN
            "portuguese", "pt" -> TranslateLanguage.PORTUGUESE
            "hindi", "hi" -> TranslateLanguage.HINDI
            "arabic", "ar" -> TranslateLanguage.ARABIC
            "russian", "ru" -> TranslateLanguage.RUSSIAN
            "vietnamese", "vi" -> TranslateLanguage.VIETNAMESE
            "thai", "th" -> TranslateLanguage.THAI
            "indonesian", "id" -> TranslateLanguage.INDONESIAN
            "turkish", "tr" -> TranslateLanguage.TURKISH
            else -> null
        }
    }

    fun canTranslateOffline(sourceLanguage: String, targetLanguage: String): Boolean {
        val src = languageCodeFor(sourceLanguage) ?: return false
        val tgt = languageCodeFor(targetLanguage) ?: return false
        return src != tgt
    }

    suspend fun translateEventsOffline(
        events: List<DialogueEvent>,
        sourceLanguage: String,
        targetLanguage: String,
    ): Result<Map<String, String>> {
        if (events.isEmpty()) return Result.success(emptyMap())
        val srcCode = languageCodeFor(sourceLanguage)
        val tgtCode = languageCodeFor(targetLanguage)
        if (srcCode == null || tgtCode == null) {
            return Result.failure(
                IllegalArgumentException("Offline pack unavailable for $sourceLanguage → $targetLanguage")
            )
        }
        if (srcCode == tgtCode) {
            return Result.success(events.associate { it.id to it.sourceText })
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcCode)
            .setTargetLanguage(tgtCode)
            .build()
        val translator = Translation.getClient(options)
        return try {
            withTimeout(30_000L) {
                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions).awaitTask()
                val out = mutableMapOf<String, String>()
                for (evt in events) {
                    val src = evt.sourceText.trim()
                    if (src.isBlank()) continue
                    val translated = runCatching {
                        translator.translate(src).awaitTask()
                    }.getOrNull()
                    if (!translated.isNullOrBlank()) {
                        out[evt.id] = translated.trim()
                    }
                }
                Result.success(out)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Offline ML Kit translation failed ($srcCode -> $tgtCode)", e)
            Result.failure(e)
        } finally {
            runCatching { translator.close() }
        }
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { ex ->
        if (cont.isActive) cont.resumeWithException(ex)
    }
    addOnCanceledListener {
        if (cont.isActive) cont.cancel()
    }
}
