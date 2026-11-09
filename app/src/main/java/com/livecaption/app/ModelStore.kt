package com.livecaption.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 다국어 인식 모델(SenseVoice: 한국어/영어/일본어/중국어)을 처음 한 번만 내려받아 폰에 저장.
 * APK가 너무 커지지 않도록 모델은 앱 안에 넣지 않고 따로 받습니다.
 */
object ModelStore {
    private const val BASE = "https://github.com/JWKIM04-exe/LiveCaption/releases/download/models/"
    private const val MODEL = "sense-voice.int8.onnx"
    private const val TOKENS = "sense-voice-tokens.txt"
    private val FILES = listOf(MODEL, TOKENS)

    private fun dir(c: Context) = File(c.filesDir, "sensevoice").apply { mkdirs() }

    fun modelFile(c: Context) = File(dir(c), MODEL)
    fun tokensFile(c: Context) = File(dir(c), TOKENS)

    fun isReady(c: Context) = FILES.all { File(dir(c), it).length() > 0 }

    /** 백그라운드 스레드에서 호출. 성공하면 true. */
    fun download(c: Context, onProgress: (String) -> Unit): Boolean {
        for (name in FILES) {
            val target = File(dir(c), name)
            if (target.length() > 0) continue
            val part = File(dir(c), "$name.part")
            try {
                val conn = URL(BASE + name).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    onProgress("다운로드 실패 (HTTP ${conn.responseCode})")
                    conn.disconnect()
                    return false
                }
                val total = conn.contentLengthLong
                var done = 0L
                var lastReport = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 2_000_000) {
                                lastReport = done
                                val mb = done / 1_000_000
                                onProgress(
                                    if (total > 0) "다국어 모델 받는 중… ${done * 100 / total}% (${mb}MB)"
                                    else "다국어 모델 받는 중… ${mb}MB"
                                )
                            }
                        }
                    }
                }
                conn.disconnect()
                if (total > 0 && part.length() != total) {
                    part.delete()
                    onProgress("다운로드가 중간에 끊겼어요. 다시 눌러 주세요.")
                    return false
                }
                if (!part.renameTo(target)) {
                    onProgress("파일 저장 실패")
                    return false
                }
            } catch (e: Exception) {
                part.delete()
                onProgress("다운로드 실패: ${e.message}")
                return false
            }
        }
        return true
    }
}
