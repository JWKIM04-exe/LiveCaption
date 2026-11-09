package com.livecaption.app

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/** 폰 안에서 동작하는 번역 (Google ML Kit). 외국어 → 한국어. 모두 백그라운드 스레드에서 호출. */
class LiveTranslator {

    companion object {
        val SOURCES = listOf(TranslateLanguage.ENGLISH, TranslateLanguage.JAPANESE, TranslateLanguage.CHINESE)

        /** 번역 모델(언어당 약 30MB)을 미리 받아둠. */
        fun prefetchAll(onProgress: (String) -> Unit): Boolean {
            val t = LiveTranslator()
            try {
                for ((i, src) in SOURCES.withIndex()) {
                    onProgress("번역 모델 받는 중… (${i + 1}/${SOURCES.size})")
                    Tasks.await(t.client(src).downloadModelIfNeeded(), 5, TimeUnit.MINUTES)
                }
                return true
            } catch (e: Exception) {
                onProgress("번역 모델 다운로드 실패: ${e.message}")
                return false
            } finally {
                t.close()
            }
        }

        private fun hangulRatio(s: String): Float {
            var letters = 0
            var hangul = 0
            for (ch in s) {
                if (Character.isLetter(ch)) {
                    letters++
                    if (ch in '가'..'힣' || ch in 'ㄱ'..'ㆎ') hangul++
                }
            }
            return if (letters == 0) 0f else hangul.toFloat() / letters
        }
    }

    private val clients = HashMap<String, Translator>()

    private fun client(src: String): Translator = clients.getOrPut(src) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build()
        )
    }

    /**
     * 인식기가 알려준 언어 태그(<|en|>, <|ja|>, <|ko|> …)와 글자를 보고 번역할 원본 언어를 고름.
     * 한국어거나 모르는 언어면 null (번역 안 함).
     */
    fun sourceFor(langTag: String, text: String): String? {
        if (hangulRatio(text) > 0.5f) return null
        val tag = langTag.trim('<', '|', '>', ' ').lowercase()
        return when (tag) {
            "en" -> TranslateLanguage.ENGLISH
            "ja" -> TranslateLanguage.JAPANESE
            "zh", "yue" -> TranslateLanguage.CHINESE
            else -> null
        }
    }

    fun translate(src: String, text: String): String? = try {
        val c = client(src)
        Tasks.await(c.downloadModelIfNeeded(), 2, TimeUnit.MINUTES)
        Tasks.await(c.translate(text), 5, TimeUnit.SECONDS)
    } catch (e: Exception) {
        null
    }

    fun close() {
        clients.values.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        clients.clear()
    }
}
