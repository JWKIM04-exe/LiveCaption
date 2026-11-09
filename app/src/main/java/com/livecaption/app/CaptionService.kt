package com.livecaption.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.hardware.input.InputManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import kotlin.math.max
import kotlin.math.min

/**
 * 소리를 받아서(폰 소리 or 마이크) 폰 안에서 한국어로 인식하고,
 * 다른 앱 위에 자막을 띄우는 서비스.
 */
class CaptionService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val ACTION_START = "com.livecaption.app.START"
        const val ACTION_STOP = "com.livecaption.app.STOP"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        private const val TAG = "LiveCaption"
        private const val CHANNEL_ID = "caption"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val CLEAR_AFTER_MS = 5000L     // 이만큼 조용하면 자막 지움
        private const val MAX_LINE_CHARS = 60         // 한 줄이 너무 길면 뒷부분만 보여줌

        private const val MODEL_DIR = "sherpa-onnx-streaming-zipformer-korean-2024-06-16"
        private const val PARTIAL_MS = 500L           // 다국어 모드: 말하는 중 중간 결과 갱신 주기
        private const val VAD_WINDOW = 512

        @Volatile
        var running = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences

    private var overlay: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var projection: MediaProjection? = null
    private var worker: Thread? = null

    @Volatile
    private var stopFlag = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null || intent.action != ACTION_START || running) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }

        val source = intent.getIntExtra(EXTRA_SOURCE, Prefs.SOURCE_PHONE)

        // 1) 반드시 먼저 포그라운드 서비스로 전환 (안드로이드 14 규칙)
        try {
            val type = if (source == Prefs.SOURCE_PHONE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // 2) 폰 소리 모드면 화면 녹화(=소리 캡처) 토큰 받기
        if (source == Prefs.SOURCE_PHONE) {
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }
            if (data == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = try {
                mpm.getMediaProjection(code, data)
            } catch (e: Exception) {
                Log.e(TAG, "getMediaProjection failed", e)
                null
            }
            if (p == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopSelf() }
                }
            }, main)
            projection = p
        }

        running = true
        stopFlag = false
        showOverlay()
        setCaption("자막 준비 중…")
        prefs.registerOnSharedPreferenceChangeListener(this)

        val multi = Prefs.lang(prefs) == Prefs.LANG_MULTI && ModelStore.isReady(this)
        worker = Thread({
            if (multi) multiLangLoop(source) else recognitionLoop(source)
        }, "caption-worker").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopFlag = true
        running = false
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
        } catch (_: Exception) {
        }
        try {
            worker?.join(1500)
        } catch (_: InterruptedException) {
        }
        worker = null
        removeOverlay()
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        super.onDestroy()
    }

    // ───────────────────────── 음성인식 ─────────────────────────

    private fun createRecognizer(): OnlineRecognizer {
        val config = OnlineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "$MODEL_DIR/decoder-epoch-99-avg-1.onnx",
                    joiner = "$MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx",
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 2,
                modelType = "zipformer",
            ),
            // 문장 끝을 빨리 잡아서 자막이 한 줄씩 넘어가도록 조정
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.0f, 0.0f),
                rule2 = EndpointRule(true, 0.8f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 10.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return OnlineRecognizer(assetManager = assets, config = config)
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(source: Int): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val builder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBuf, SAMPLE_RATE * 2 * 4))

        if (source == Prefs.SOURCE_PHONE) {
            val p = projection ?: throw IllegalStateException("no projection")
            val capture = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            builder.setAudioPlaybackCaptureConfig(capture)
        } else {
            builder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        return builder.build()
    }

    private fun recognitionLoop(source: Int) {
        var recognizer: OnlineRecognizer? = null
        var record: AudioRecord? = null
        try {
            recognizer = createRecognizer()
            val stream = recognizer.createStream()

            record = createAudioRecord(source)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord init failed")
            }
            record.startRecording()

            setCaption(if (source == Prefs.SOURCE_PHONE) "🎧 듣는 중… 영상을 틀어보세요" else "🎤 듣는 중…")
            var hint = true

            val buf = ShortArray(SAMPLE_RATE / 10) // 0.1초씩
            val committed = ArrayDeque<String>()
            var shown = ""
            var lastChange = SystemClock.elapsedRealtime()

            while (!stopFlag) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("AudioRecord read error $n")
                if (n == 0) continue

                val samples = FloatArray(n) { buf[it] / 32768.0f }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                }

                val partial = recognizer.getResult(stream).text.trim()
                val endpoint = recognizer.isEndpoint(stream)
                val maxLines = Prefs.lines(prefs)

                if (endpoint) {
                    if (partial.isNotEmpty()) committed.addLast(partial)
                    recognizer.reset(stream)
                }
                while (committed.size > maxLines) committed.removeFirst()

                val lines = ArrayList<String>(committed)
                if (!endpoint && partial.isNotEmpty()) lines.add(partial)
                while (lines.size > maxLines) lines.removeAt(0)
                val text = lines.joinToString("\n") { tail(it) }

                val now = SystemClock.elapsedRealtime()
                if (text.isNotEmpty() && text != shown) {
                    shown = text
                    lastChange = now
                    hint = false
                    setCaption(text)
                } else if (!hint && shown.isNotEmpty() && now - lastChange > CLEAR_AFTER_MS) {
                    committed.clear()
                    shown = ""
                    setCaption("")
                }
            }
            stream.release()
        } catch (e: Throwable) {
            Log.e(TAG, "recognition error", e)
            setCaption("⚠️ 오류: ${e.message ?: e.javaClass.simpleName}")
            main.postDelayed({ stopSelf() }, 4000)
        } finally {
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            record?.release()
            recognizer?.release()
        }
    }

    // ───────────────────── 다국어 모드 (한/영/일 + 번역) ─────────────────────

    private class Line(val main: String, val original: String?)

    private fun decodeOnce(recognizer: OfflineRecognizer, samples: FloatArray): OfflineRecognizerResult {
        val s = recognizer.createStream()
        s.acceptWaveform(samples, SAMPLE_RATE)
        recognizer.decode(s)
        val r = recognizer.getResult(s)
        s.release()
        return r
    }

    private fun toLine(r: OfflineRecognizerResult, tr: LiveTranslator): Line? {
        val text = r.text.trim()
        if (text.isEmpty()) return null
        val src = tr.sourceFor(r.lang, text) ?: return Line(text, null)
        val ko = tr.translate(src, text)?.trim()
        return if (ko.isNullOrEmpty()) Line(text, null) else Line(ko, text)
    }

    private fun render(lines: List<Line>): CharSequence {
        val showOrig = Prefs.showOriginal(prefs)
        val sb = SpannableStringBuilder()
        lines.forEachIndexed { i, l ->
            if (i > 0) sb.append("\n")
            if (showOrig && l.original != null) {
                val start = sb.length
                sb.append(tail(l.original)).append("\n")
                sb.setSpan(RelativeSizeSpan(0.7f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.rgb(205, 205, 205)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append(tail(l.main))
        }
        return sb
    }

    private fun multiLangLoop(source: Int) {
        var recognizer: OfflineRecognizer? = null
        var vad: Vad? = null
        var record: AudioRecord? = null
        val translator = LiveTranslator()
        try {
            setCaption("다국어 모델 불러오는 중…")
            recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = ModelStore.modelFile(this).absolutePath,
                            language = "",
                            useInverseTextNormalization = true,
                        ),
                        tokens = ModelStore.tokensFile(this).absolutePath,
                        numThreads = 2,
                    ),
                )
            )
            vad = Vad(
                assetManager = assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 0.35f,
                        minSpeechDuration = 0.25f,
                        windowSize = VAD_WINDOW,
                        maxSpeechDuration = 8.0f,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                ),
            )

            record = createAudioRecord(source)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord init failed")
            }
            record.startRecording()
            setCaption(if (source == Prefs.SOURCE_PHONE) "🎧 듣는 중… (한·영·일 자동)" else "🎤 듣는 중… (한·영·일 자동)")
            var hint = true

            val buf = ShortArray(SAMPLE_RATE / 10)
            var audio = FloatArray(SAMPLE_RATE * 20)
            var len = 0          // 쌓인 샘플 수
            var offset = 0       // VAD에 넣은 위치
            var speechStart = -1 // 지금 말하는 구간 시작 (-1 = 조용함)
            var lastPartialAt = 0L

            val committed = ArrayDeque<Line>()
            var partial: Line? = null
            var shownKey = ""
            var lastChange = SystemClock.elapsedRealtime()

            while (!stopFlag) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("AudioRecord read error $n")
                if (n == 0) continue

                if (len + n > audio.size) audio = audio.copyOf(max(audio.size * 2, len + n))
                for (i in 0 until n) audio[len + i] = buf[i] / 32768.0f
                len += n

                while (offset + VAD_WINDOW <= len) {
                    vad.acceptWaveform(audio.copyOfRange(offset, offset + VAD_WINDOW))
                    offset += VAD_WINDOW
                    if (speechStart < 0 && vad.isSpeechDetected()) {
                        speechStart = max(0, offset - SAMPLE_RATE * 4 / 10) // 0.4초 앞부터
                        lastPartialAt = 0L
                    }
                }

                // 문장이 끝났으면 최종 인식 + 번역
                var segmentDone = false
                while (!vad.empty()) {
                    val seg = vad.front()
                    vad.pop()
                    toLine(decodeOnce(recognizer, seg.samples), translator)?.let { committed.addLast(it) }
                    partial = null
                    segmentDone = true
                }
                if (segmentDone) {
                    speechStart = if (vad.isSpeechDetected()) offset else -1
                }

                // 말하는 중이면 중간 결과
                val now = SystemClock.elapsedRealtime()
                if (!segmentDone && speechStart >= 0 && offset - speechStart > SAMPLE_RATE / 2 &&
                    now - lastPartialAt > PARTIAL_MS
                ) {
                    partial = toLine(decodeOnce(recognizer, audio.copyOfRange(speechStart, offset)), translator)
                    lastPartialAt = SystemClock.elapsedRealtime()
                }

                // 조용할 때는 오래된 소리를 버려서 메모리 유지
                // (말하는 중이면 그 구간 시작 전까지만 버림 — 노래처럼 계속 이어지는 소리에도 안전)
                val base = if (speechStart >= 0) speechStart else offset - SAMPLE_RATE * 4 / 10
                if (base > SAMPLE_RATE) {
                    System.arraycopy(audio, base, audio, 0, len - base)
                    len -= base
                    offset -= base
                    if (speechStart >= 0) speechStart -= base
                }

                val maxLines = Prefs.lines(prefs)
                while (committed.size > maxLines) committed.removeFirst()
                val lines = ArrayList<Line>(committed)
                partial?.let { lines.add(it) }
                while (lines.size > maxLines) lines.removeAt(0)

                val key = lines.joinToString("|") { it.main + "/" + it.original } + Prefs.showOriginal(prefs)
                val t = SystemClock.elapsedRealtime()
                if (lines.isNotEmpty() && key != shownKey) {
                    shownKey = key
                    lastChange = t
                    hint = false
                    setCaption(render(lines))
                } else if (!hint && shownKey.isNotEmpty() && lines.isNotEmpty() && t - lastChange > CLEAR_AFTER_MS && speechStart < 0) {
                    committed.clear()
                    partial = null
                    shownKey = ""
                    setCaption("")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "multilang error", e)
            setCaption("⚠️ 오류: ${e.message ?: e.javaClass.simpleName}")
            main.postDelayed({ stopSelf() }, 4000)
        } finally {
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            record?.release()
            vad?.release()
            recognizer?.release()
            translator.close()
        }
    }

    private fun tail(s: String): String =
        if (s.length <= MAX_LINE_CHARS) s else "…" + s.substring(s.length - MAX_LINE_CHARS)

    // ───────────────────────── 화면 위 자막 ─────────────────────────

    private fun showOverlay() {
        if (overlay != null) return
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            setLineSpacing(0f, 1.1f)
            val pad = dp(10)
            setPadding(pad, dp(6), pad, dp(6))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        // 안드로이드 12+: 터치가 아래 앱으로 통과되려면 창 불투명도가 기준값(보통 0.8) 이하여야 함
        lp.alpha = maxTouchPassAlpha()
        applyStyle(tv, lp)
        try {
            wm.addView(tv, lp)
            overlay = tv
            overlayParams = lp
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
        }
    }

    private fun maxTouchPassAlpha(): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val im = getSystemService(Context.INPUT_SERVICE) as InputManager
            min(0.8f, im.maximumObscuringOpacityForTouch)
        } else {
            1.0f
        }
    }

    private fun applyStyle(tv: TextView, lp: WindowManager.LayoutParams) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Prefs.font(prefs).toFloat())
        val bgAlpha = (Prefs.bg(prefs) * 255 / 100).coerceIn(0, 255)
        tv.setBackgroundColor(Color.argb(bgAlpha, 0, 0, 0))
        lp.gravity = when (Prefs.pos(prefs)) {
            0 -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            1 -> Gravity.CENTER
            else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        lp.y = if (Prefs.pos(prefs) == 1) 0 else dp(48)
    }

    private fun setCaption(text: CharSequence) {
        main.post {
            val tv = overlay ?: return@post
            tv.text = text
            tv.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun removeOverlay() {
        overlay?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
        overlayParams = null
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        main.post {
            val tv = overlay ?: return@post
            val lp = overlayParams ?: return@post
            applyStyle(tv, lp)
            try {
                wm.updateViewLayout(tv, lp)
            } catch (_: Exception) {
            }
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ───────────────────────── 알림 ─────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "실시간 자막", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("실시간 자막 켜짐")
            .setContentText("눌러서 설정 · [끄기]로 종료")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "끄기", stopPi
                ).build()
            )
            .build()
    }
}
