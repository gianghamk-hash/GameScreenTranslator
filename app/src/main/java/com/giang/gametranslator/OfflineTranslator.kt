package com.giang.gametranslator
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OfflineTranslator {
    private val client = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.VIETNAMESE).build())
    @Volatile private var ready = false
    fun prepare(onStatus: (Boolean) -> Unit) {
        client.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build())
            .addOnSuccessListener { ready = true; onStatus(true) }
            .addOnFailureListener { onStatus(false) }
    }
    suspend fun translate(text: String): String? {
        if (!ready) return null
        return suspendCancellableCoroutine { cont ->
            client.translate(text)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }
    }
    fun close() = client.close()
}
