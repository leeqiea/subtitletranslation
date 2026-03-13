@file:Suppress("DEPRECATION", "SameParameterValue")

package com.example.subtitletranslation.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import com.example.subtitletranslation.MainActivity
import com.example.subtitletranslation.R
import com.example.subtitletranslation.model.ModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

class OverlayService : Service() {

    companion object {
        private const val TAG = "SubtitleTranslation/Overlay"
        const val ACTION_START = "com.example.subtitletranslation.action.START"
        const val ACTION_UPDATE = "com.example.subtitletranslation.action.UPDATE"
        const val ACTION_STOP = "com.example.subtitletranslation.action.STOP"

        const val ACTION_START_CAPTURE = "com.example.subtitletranslation.action.START_CAPTURE"
        const val ACTION_START_MIC_CAPTURE = "com.example.subtitletranslation.action.START_MIC_CAPTURE"

        const val EXTRA_TEXT = "subtitle_text"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "subtitle_overlay_channel"
        private const val NOTIF_ID = 1001

        private const val PREF_DISPLAY_MODE = "subtitle_display_mode"
        private const val DISPLAY_TRANSLATION_ONLY = "translation_only"
        private const val PREF_OVERLAY_X = "overlay_x"
        private const val PREF_OVERLAY_Y = "overlay_y"
        private const val PREF_OVERLAY_SCALE = "overlay_scale"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var wm: WindowManager
    private var overlayView: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private var overlayScale: Float = 1.0f
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile
    private var capturing = false

    @Volatile
    private var blockDetected = false

    @Volatile
    private var captureStartInFlight = false

    @Volatile
    private var captureUsesProjection = false

    @Volatile
    private var captureUsesMicrophone = false

    private var lastOriginal: String = ""
    private var lastTranslated: String = ""
    private var isForeground = false
    private var warnedNoMediaProjectionFgs = false

    private var tvOriginal: TextView? = null
    private var tvTranslated: TextView? = null

    private val translateService = TranslateService()
    private val recLock = Any()
    private val serviceExecutor = Executors.newSingleThreadExecutor()
    private val recognizerExecutor = Executors.newSingleThreadExecutor()
    private var lastLoggedTargetLang: String? = null

    override fun onCreate() {
        super.onCreate()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        overlayScale = prefs.getFloat(PREF_OVERLAY_SCALE, 1.0f)
            .coerceIn(0.6f, 2.5f)
        val savedX = prefs.getInt(PREF_OVERLAY_X, 60)
        val savedY = prefs.getInt(PREF_OVERLAY_Y, 160)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        createNotificationChannel()
        lastOriginal = getString(R.string.overlay_ready)
        lastTranslated = getString(R.string.overlay_translated_sample)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            ACTION_STOP -> {
                stopOverlayAndSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                ensureForeground(
                    needMediaProjection = hasActiveProjectionSession(),
                    needMicrophone = captureUsesMicrophone
                )
                ensureOverlayCreated()
                intent.getStringExtra(EXTRA_TEXT)?.let { updateSubtitle(it, "") }
            }

            ACTION_UPDATE, null -> {
                if (overlayView == null) {
                    if (!capturing && !hasActiveProjectionSession()) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    return START_STICKY
                }
                ensureForeground(
                    needMediaProjection = hasActiveProjectionSession(),
                    needMicrophone = captureUsesMicrophone
                )
                val text = intent?.getStringExtra(EXTRA_TEXT)
                if (text != null) {
                    updateSubtitle(text, "")
                } else {
                    refreshSubtitleView()
                }
            }

            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    captureStartInFlight = true
                    if (!ensureForeground(needMediaProjection = true, needMicrophone = false)) {
                        captureStartInFlight = false
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    ensureOverlayCreated()
                    updateSubtitle(getString(R.string.overlay_auth_received_start_capture), "")
                    runOnServiceExecutor {
                        startAudioCapture(resultCode, resultData)
                    }
                } else {
                    captureStartInFlight = false
                    if (!capturing && !hasActiveProjectionSession()) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
            }

            ACTION_START_MIC_CAPTURE -> {
                captureStartInFlight = false
                if (!ensureForeground(needMediaProjection = false, needMicrophone = true)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureOverlayCreated()
                runOnServiceExecutor {
                    startMicCapture()
                }
            }
        }

        return START_STICKY
    }

    private fun hasActiveProjectionSession(): Boolean {
        return captureStartInFlight || captureUsesProjection || projection != null
    }

    private fun messageOrUnknown(message: String?): String {
        return message ?: getString(R.string.error_unknown)
    }

    private fun rms01(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / n) / 32768.0
    }

    @SuppressLint("ServiceCast")
    private fun startAudioCapture(resultCode: Int, resultData: Intent) {
        if (capturing) {
            captureStartInFlight = false
            return
        }

        try {
            // 录音权限必须在 Activity 里申请好；Service 里只能检查
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                updateSubtitle(getString(R.string.overlay_missing_record_permission), "")
                capturing = false
                return
            }

            capturing = true
            captureUsesProjection = false
            captureUsesMicrophone = false
            blockDetected = false
            silentMs = 0L
            sawAnyText = false
            updateSubtitle(getString(R.string.overlay_starting_system_capture), "")
            lastFinalOriginal = ""

            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = try {
                mpm.getMediaProjection(resultCode, resultData)
            } catch (t: Throwable) {
                val msg = if (t is SecurityException) {
                    getString(R.string.overlay_cannot_capture_system_audio_restricted)
                } else {
                    getString(R.string.overlay_cannot_capture_system_audio_error, messageOrUnknown(t.message))
                }
                updateSubtitle(msg, "")
                capturing = false
                return
            }
            if (p == null) {
                updateSubtitle(getString(R.string.overlay_cannot_capture_system_audio_auth_invalid), "")
                capturing = false
                return
            }
            projection = p
            captureUsesProjection = true

            val config = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .build()

            val sampleRate = 16000
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            try {
                audioRecord = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf * 2)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    updateSubtitle(getString(R.string.overlay_audio_capture_init_failed), "")
                    stopAudioCapture()
                    return
                }

                audioRecord!!.startRecording()
            } catch (t: Throwable) {
                val msg = messageOrUnknown(t.message)
                if (msg.contains("register audio policy", ignoreCase = true) ||
                    msg.contains("audio policy", ignoreCase = true) ||
                    msg.contains("permission denied", ignoreCase = true)
                ) {
                    updateSubtitle(getString(R.string.overlay_system_capture_failed_switch_to_mic), "")
                    stopAudioCapture()
                    startMicCapture()
                    return
                }
                updateSubtitle(getString(R.string.overlay_audio_capture_failed, msg), "")
                stopAudioCapture()
                return
            }

            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                updateSubtitle(getString(R.string.overlay_media_volume_zero), "")
            }

            if (ensureRecognizer() == null) {
                stopAudioCapture()
                return
            }

            startRecognizerLoop(minBuf, sampleRate, detectPlaybackBlock = false, threadName = "playback-capture-thread")
        } catch (t: Throwable) {
            updateSubtitle(getString(R.string.overlay_start_capture_failed, messageOrUnknown(t.message)), "")
            stopAudioCapture()
        } finally {
            captureStartInFlight = false
        }
    }

    private fun startMicCapture() {
        if (capturing) return
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                updateSubtitle(getString(R.string.overlay_missing_record_permission), "")
                return
            }

            if (!ensureForeground(needMediaProjection = false, needMicrophone = true)) {
                return
            }

            capturing = true
            captureUsesProjection = false
            captureUsesMicrophone = true
            blockDetected = false
            silentMs = 0L
            sawAnyText = false
            updateSubtitle(getString(R.string.overlay_starting_acr_capture), "")
            lastFinalOriginal = ""

            val sampleRate = 16000
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            try {
                audioRecord = buildMicAudioRecord(format, minBuf)
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    updateSubtitle(getString(R.string.overlay_mic_capture_init_failed), "")
                    stopAudioCapture()
                    return
                }
                audioRecord!!.startRecording()
            } catch (t: Throwable) {
                updateSubtitle(getString(R.string.overlay_mic_capture_failed, messageOrUnknown(t.message)), "")
                stopAudioCapture()
                return
            }

            if (ensureRecognizer() == null) {
                stopAudioCapture()
                return
            }

            startRecognizerLoop(minBuf, sampleRate, detectPlaybackBlock = false, threadName = "mic-acr-thread")
        } catch (t: Throwable) {
            updateSubtitle(getString(R.string.overlay_start_acr_failed, messageOrUnknown(t.message)), "")
            stopAudioCapture()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildMicAudioRecord(format: AudioFormat, minBuf: Int): AudioRecord {
        return try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        } catch (_: Throwable) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        }
    }

    private fun startRecognizerLoop(
        minBuf: Int,
        sampleRate: Int,
        detectPlaybackBlock: Boolean,
        threadName: String
    ) {
        captureThread = Thread {
            try {
                val buf = ShortArray(minBuf)
                var lastUi = 0L

                while (capturing) {
                    val n = try {
                        audioRecord?.read(buf, 0, buf.size) ?: break
                    } catch (e: Exception) {
                        mainHandler.post {
                            updateSubtitle(
                                getString(R.string.overlay_audio_read_failed, messageOrUnknown(e.message)),
                                ""
                            )
                            stopAudioCapture()
                        }
                        break
                    }
                    if (n <= 0) continue

                    if (detectPlaybackBlock) {
                        val rms = rms01(buf, n)
                        val frameMs = (n.toLong() * 1000L) / sampleRate
                        if (audioManager.isMusicActive) {
                            if (rms < 0.002) {
                                silentMs += frameMs
                            } else {
                                silentMs = 0L
                                if (blockDetected) blockDetected = false
                            }
                        } else {
                            silentMs = 0L
                        }
                    }

                    val bytes = shortToBytesLE(buf, n)
                    val result = try {
                        synchronized(recLock) {
                            val r = voskRec ?: return@synchronized null
                            val isFinal = r.acceptWaveForm(bytes, bytes.size)
                            val json = if (isFinal) r.result else r.partialResult
                            isFinal to json
                        }
                    } catch (t: Throwable) {
                        mainHandler.post {
                            updateSubtitle(
                                getString(R.string.overlay_recognizer_thread_error, messageOrUnknown(t.message)),
                                ""
                            )
                        }
                        null
                    } ?: continue

                    val now = System.currentTimeMillis()
                    if (now - lastUi >= 250) {
                        val isFinal = result.first
                        val json = result.second
                        val text = extractText(json, if (isFinal) "text" else "partial")
                        if (text.isNotBlank()) {
                            if (blockDetected) blockDetected = false
                            sawAnyText = true
                            if (isFinal) {
                                handleFinalText(text)
                            } else {
                                updateSubtitle(text, "...")
                            }
                        }
                        lastUi = now
                    }

                    if (detectPlaybackBlock && !sawAnyText && silentMs >= 10_000L) {
                        handleCaptureBlocked()
                    }
                }
            } catch (t: Throwable) {
                mainHandler.post {
                    updateSubtitle(
                        getString(R.string.overlay_capture_thread_crash, messageOrUnknown(t.message)),
                        ""
                    )
                }
            }
        }.apply {
            name = threadName
            start()
        }
    }

    private fun stopAudioCapture() {
        capturing = false
        captureStartInFlight = false
        captureUsesProjection = false
        captureUsesMicrophone = false
        translateService.cancelPending()
        try {
            captureThread?.join(300)
        } catch (_: Exception) {
        }
        captureThread = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        projection?.stop()
        projection = null
    }

    private var lastFinalOriginal: String = ""

    private fun handleFinalText(text: String) {
        if (text == lastFinalOriginal) return
        lastFinalOriginal = text
        updateSubtitle(text, "...")
        translateAndUpdate(text)
    }

    private var silentMs: Long = 0
    private var sawAnyText: Boolean = false

    private fun handleCaptureBlocked() {
        if (blockDetected) return
        blockDetected = true
        mainHandler.post {
            val msg = getString(R.string.overlay_app_disallows_audio_capture)
            updateSubtitle(msg, msg)
        }
    }

    private fun translateAndUpdate(text: String) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val targetLang = prefs.getString("translate_target_lang", TranslateLanguage.CHINESE)
            ?: TranslateLanguage.CHINESE
        if (targetLang != lastLoggedTargetLang) {
            Log.i(TAG, "translateAndUpdate: targetLang from prefs = $targetLang")
            lastLoggedTargetLang = targetLang
        }
        translateService.translate(
            text,
            targetLang,
            { original, translated ->
                updateSubtitle(original, translated)
            },
            { sourceLang ->
                if (!sourceLang.isNullOrBlank()) {
                    Log.d(TAG, "translateAndUpdate: detected sourceLang=$sourceLang, targetLang=$targetLang")
                }
            }
        )
    }

    private fun resolveInstalledModelPath(
        prefs: android.content.SharedPreferences,
        modelId: String
    ): String? {
        val saved = prefs.getString("asr_model_path_$modelId", null)
        if (!saved.isNullOrBlank() && File(saved).exists()) return saved
        val resolved = ModelManager.resolveInstalledPath(this, modelId)
        return resolved?.absolutePath
    }

    private var currentFgsType: Int = 0

    @SuppressLint("ForegroundServiceType")
    private fun ensureForeground(
        needMediaProjection: Boolean,
        needMicrophone: Boolean
    ): Boolean {
        val notification = buildNotification()
        val effectiveNeedMediaProjection = needMediaProjection || hasActiveProjectionSession()

        val desiredType = if (Build.VERSION.SDK_INT >= 34) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (effectiveNeedMediaProjection) {
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            if (needMicrophone) {
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            t
        } else 0
        val fallbackType = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0

        // 已经前台且类型不变就不重复调用
        if (isForeground && desiredType == currentFgsType) return true

        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, desiredType)
            isForeground = true
            currentFgsType = desiredType
            return true
        } catch (e: Exception) {
            if (needMicrophone) {
                updateSubtitle(
                    getString(R.string.overlay_start_foreground_failed, messageOrUnknown(e.message)),
                    ""
                )
                return false
            }
            if (desiredType != fallbackType) {
                try {
                    ServiceCompat.startForeground(this, NOTIF_ID, notification, fallbackType)
                    isForeground = true
                    currentFgsType = fallbackType
                    if (!warnedNoMediaProjectionFgs) {
                        warnedNoMediaProjectionFgs = true
                        updateSubtitle(getString(R.string.overlay_fgs_media_projection_downgraded), "")
                    }
                    return true
                } catch (e2: Exception) {
                    updateSubtitle(
                        getString(R.string.overlay_start_foreground_failed, messageOrUnknown(e2.message)),
                        ""
                    )
                    return false
                }
            } else {
                updateSubtitle(
                    getString(R.string.overlay_start_foreground_failed, messageOrUnknown(e.message)),
                    ""
                )
                return false
            }
        }
    }

    private fun runOnServiceExecutor(task: () -> Unit) {
        try {
            serviceExecutor.execute(task)
        } catch (_: Exception) {
        }
    }

    private fun runOnRecognizerExecutor(task: () -> Unit) {
        try {
            recognizerExecutor.execute(task)
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_title_overlay_running))
            .setContentText(getString(R.string.notif_text_tap_stop))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
    }

    @SuppressLint("InflateParams")
    private fun ensureOverlayCreated() {
        if (overlayView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_subtitle, null)
        val tvO = view.findViewById<TextView>(R.id.tvSubtitleOriginal)
        val tvT = view.findViewById<TextView>(R.id.tvSubtitleTranslated)
        val close = view.findViewById<TextView>(R.id.btnClose)

        close.setOnClickListener { stopOverlayAndSelf() }
        view.setOnTouchListener(DragTouchListener())
        view.scaleX = overlayScale
        view.scaleY = overlayScale

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            updateSubtitle(getString(R.string.overlay_create_failed, messageOrUnknown(e.message)), "")
            return
        }
        overlayView = view
        tvOriginal = tvO
        tvTranslated = tvT
        refreshSubtitleView()
    }

    private fun updateSubtitle(original: String, translated: String) {
        lastOriginal = original
        lastTranslated = translated
        refreshSubtitleView()
    }

    private fun refreshSubtitleView() {
        val translationOnly = isTranslationOnly()
        val original = lastOriginal
        val translated = lastTranslated
        mainHandler.post {
            if (translationOnly) {
                tvOriginal?.visibility = View.GONE
                tvTranslated?.visibility = View.VISIBLE
                tvTranslated?.text = translated
            } else {
                tvOriginal?.visibility = View.VISIBLE
                tvTranslated?.visibility = View.VISIBLE
                tvOriginal?.text = original
                tvTranslated?.text = translated
            }
        }
    }

    private fun isTranslationOnly(): Boolean {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        return prefs.getString(PREF_DISPLAY_MODE, null) == DISPLAY_TRANSLATION_ONLY
    }

    private fun stopOverlayAndSelf() {
        stopAudioCapture()
        overlayView?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        tvOriginal = null
        tvTranslated = null

        translateService.close()

        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        captureStartInFlight = false
        stopAudioCapture()
        serviceExecutor.shutdownNow()
        recognizerExecutor.shutdownNow()
        overlayView?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        tvOriginal = null
        tvTranslated = null
        translateService.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var scaling = false
        private val scaleDetector = ScaleGestureDetector(
            this@OverlayService,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    overlayScale = (overlayScale * detector.scaleFactor).coerceIn(0.6f, 2.5f)
                    overlayView?.let {
                        it.scaleX = overlayScale
                        it.scaleY = overlayScale
                    }
                    scaling = true
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    saveOverlayScale()
                    scaling = false
                }
            }
        )

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) return true
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (scaling) return true
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    overlayView?.let { wm.updateViewLayout(it, params) }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveOverlayPosition()
                    return true
                }
            }
            return false
        }
    }

    private fun saveOverlayPosition() {
        getSharedPreferences("settings", MODE_PRIVATE).edit {
            putInt(PREF_OVERLAY_X, params.x)
                .putInt(PREF_OVERLAY_Y, params.y)
        }
    }

    private fun saveOverlayScale() {
        getSharedPreferences("settings", MODE_PRIVATE).edit {
            putFloat(PREF_OVERLAY_SCALE, overlayScale)
        }
    }

    private var voskModel: Model? = null
    private var voskRec: Recognizer? = null
    private var currentModelId: String? = null
    private fun ensureRecognizer(): Recognizer? {
        return try {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val modelId = prefs.getString("asr_model_id", null)
                ?: prefs.getString("asr_lang", null) // 兼容旧逻辑

            if (modelId.isNullOrBlank()) {
                updateSubtitle(getString(R.string.overlay_model_not_selected), "")
                return null
            }

            val modelPath = resolveInstalledModelPath(prefs, modelId)

            if (modelPath.isNullOrBlank()) {
                updateSubtitle(getString(R.string.overlay_model_not_installed, modelId), "")
                return null
            }

            val modelDir = File(modelPath)
            if (!modelDir.exists()) {
                updateSubtitle(getString(R.string.overlay_model_path_missing, modelPath), "")
                return null
            }

            val validationError = ModelManager.validateInstalledModel(this, modelDir)
            if (validationError != null) {
                updateSubtitle(validationError, "")
                return null
            }

            prefs.edit {
                putString("asr_model_path_$modelId", modelDir.absolutePath)
            }

            if (voskRec != null && currentModelId == modelId) return voskRec

            switchRecognizerToModel(modelId, modelDir.absolutePath)
            synchronized(recLock) { voskRec }
        } catch (t: Throwable) {
            updateSubtitle(getString(R.string.overlay_recognizer_prepare_failed, messageOrUnknown(t.message)), "")
            null
        }
    }

    private fun stopRecognizer() {
        synchronized(recLock) {
            try {
                voskRec?.close()
            } catch (_: Exception) {
            }
            voskRec = null
            try {
                voskModel?.close()
            } catch (_: Exception) {
            }
            voskModel = null
            currentModelId = null
        }
    }

    private fun switchRecognizerToModel(modelId: String, modelPath: String) {
        if (currentModelId == modelId && synchronized(recLock) { voskRec != null }) return
        val modelDir = File(modelPath)
        if (!modelDir.exists() || !modelDir.isDirectory) {
            updateSubtitle(getString(R.string.overlay_model_path_missing, modelPath), "")
            return
        }
        val newModel = try {
            Model(modelDir.absolutePath)
        } catch (t: Throwable) {
            updateSubtitle(getString(R.string.overlay_model_load_failed, messageOrUnknown(t.message)), "")
            return
        }
        val newRec = try {
            Recognizer(newModel, 16000.0f)
        } catch (t: Throwable) {
            try {
                newModel.close()
            } catch (_: Exception) {
            }
            updateSubtitle(getString(R.string.overlay_recognizer_init_failed, messageOrUnknown(t.message)), "")
            return
        }
        synchronized(recLock) {
            try {
                voskRec?.close()
            } catch (_: Exception) {
            }
            try {
                voskModel?.close()
            } catch (_: Exception) {
            }
            voskModel = newModel
            voskRec = newRec
            currentModelId = modelId
        }
    }

    private fun shortToBytesLE(src: ShortArray, n: Int): ByteArray {
        val out = ByteArray(n * 2)
        var j = 0
        for (i in 0 until n) {
            val v = src[i].toInt()
            out[j++] = (v and 0xFF).toByte()
            out[j++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun extractText(json: String, key: String): String =
        try {
            org.json.JSONObject(json).optString(key, "")
        } catch (_: Exception) {
            ""
        }

}
