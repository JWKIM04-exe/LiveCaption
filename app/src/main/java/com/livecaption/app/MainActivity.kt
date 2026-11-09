package com.livecaption.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PROJECTION = 2
        const val MIN_FONT = 14
    }

    private lateinit var status: TextView
    private lateinit var fontLabel: TextView
    private lateinit var bgLabel: TextView
    private lateinit var linesLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = Prefs.get(this)
        status = findViewById(R.id.status)
        fontLabel = findViewById(R.id.fontLabel)
        bgLabel = findViewById(R.id.bgLabel)
        linesLabel = findViewById(R.id.linesLabel)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_STOP))
            status.postDelayed({ updateStatus() }, 400)
        }

        // 소리 종류
        val sourceGroup = findViewById<RadioGroup>(R.id.sourceGroup)
        sourceGroup.check(if (Prefs.source(prefs) == Prefs.SOURCE_MIC) R.id.srcMic else R.id.srcPhone)
        sourceGroup.setOnCheckedChangeListener { _, id ->
            prefs.edit().putInt(Prefs.SOURCE, if (id == R.id.srcMic) Prefs.SOURCE_MIC else Prefs.SOURCE_PHONE).apply()
        }

        // 위치
        val posGroup = findViewById<RadioGroup>(R.id.posGroup)
        posGroup.check(
            when (Prefs.pos(prefs)) {
                0 -> R.id.posTop
                1 -> R.id.posMiddle
                else -> R.id.posBottom
            }
        )
        posGroup.setOnCheckedChangeListener { _, id ->
            val v = when (id) {
                R.id.posTop -> 0
                R.id.posMiddle -> 1
                else -> 2
            }
            prefs.edit().putInt(Prefs.POS, v).apply()
        }

        // 글자 크기
        val fontSeek = findViewById<SeekBar>(R.id.fontSeek)
        fontSeek.progress = Prefs.font(prefs) - MIN_FONT
        fontLabel.text = "글자 크기: ${Prefs.font(prefs)}"
        fontSeek.setOnSeekBarChangeListener(simpleSeek { v ->
            val size = v + MIN_FONT
            fontLabel.text = "글자 크기: $size"
            prefs.edit().putInt(Prefs.FONT, size).apply()
        })

        // 배경 진하기
        val bgSeek = findViewById<SeekBar>(R.id.bgSeek)
        bgSeek.progress = Prefs.bg(prefs)
        bgLabel.text = "자막 배경 진하기: ${Prefs.bg(prefs)}%"
        bgSeek.setOnSeekBarChangeListener(simpleSeek { v ->
            bgLabel.text = "자막 배경 진하기: $v%"
            prefs.edit().putInt(Prefs.BG, v).apply()
        })

        // 줄 수
        val linesSeek = findViewById<SeekBar>(R.id.linesSeek)
        linesSeek.progress = Prefs.lines(prefs) - 1
        linesLabel.text = "보여줄 줄 수: ${Prefs.lines(prefs)}줄"
        linesSeek.setOnSeekBarChangeListener(simpleSeek { v ->
            linesLabel.text = "보여줄 줄 수: ${v + 1}줄"
            prefs.edit().putInt(Prefs.LINES, v + 1).apply()
        })
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val running = CaptionService.running
        status.text = buildString {
            append(if (overlayOk) "✅ 다른 앱 위에 표시: 허용됨" else "⚠️ 다른 앱 위에 표시: 꺼져 있음 (①을 눌러 켜주세요)")
            append("\n")
            append(if (running) "🟢 자막 켜짐 – 이제 유튜브 등을 열어보세요" else "⚪ 자막 꺼짐")
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 '다른 앱 위에 표시'를 허용해 주세요", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        proceed()
    }

    private fun proceed() {
        val source = Prefs.source(Prefs.get(this))
        if (source == Prefs.SOURCE_MIC) {
            startCaption(source, 0, null)
        } else {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // 안드로이드 14+: '앱 하나만' 대신 전체 화면(=모든 앱 소리)을 쓰도록 지정
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                mpm.createScreenCaptureIntent()
            }
            startActivityForResult(intent, REQ_PROJECTION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            proceed()
        } else {
            Toast.makeText(this, "소리를 듣기 위해 '마이크(오디오 녹음)' 권한이 꼭 필요해요", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            startCaption(Prefs.SOURCE_PHONE, resultCode, data)
        } else {
            Toast.makeText(this, "허용하지 않아서 시작하지 못했어요", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCaption(source: Int, code: Int, data: Intent?) {
        val i = Intent(this, CaptionService::class.java).apply {
            action = CaptionService.ACTION_START
            putExtra(CaptionService.EXTRA_SOURCE, source)
            putExtra(CaptionService.EXTRA_CODE, code)
            if (data != null) putExtra(CaptionService.EXTRA_DATA, data)
        }
        startForegroundService(i)
        Toast.makeText(this, "자막을 켰어요. 이제 유튜브를 열어보세요!", Toast.LENGTH_LONG).show()
        status.postDelayed({ updateStatus() }, 600)
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
