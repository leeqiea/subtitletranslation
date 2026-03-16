package com.example.subtitletranslation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import com.example.subtitletranslation.MainActivity
import com.example.subtitletranslation.R
import com.example.subtitletranslation.model.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 前台下载服务，负责多 GB 模型的断点续传、解压、安装和通知。
 */
class ModelDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "model_download_channel"
        private const val NOTIFICATION_ID = 2001
        private const val PREFS_SETTINGS = "settings"

        const val ACTION_START_DOWNLOAD = "com.example.subtitletranslation.action.START_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.example.subtitletranslation.action.PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.example.subtitletranslation.action.RESUME_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.subtitletranslation.action.CANCEL_DOWNLOAD"

        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_MODEL_URL = "model_url"
        const val EXTRA_MODEL_LANG = "model_lang"
        const val EXTRA_LANG_KEY = "lang_key"
        const val EXTRA_SOURCE_LANG = "source_lang"
        const val EXTRA_SWITCH_NOW = "switch_now"
    }

    enum class DownloadStatus {
        IDLE,
        DOWNLOADING,
        PAUSED,
        EXTRACTING,
        VALIDATING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    data class DownloadProgress(
        val modelId: String,
        val status: DownloadStatus,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percentage: Int,
        val speedBytesPerSecond: Long,
        val remainingTimeSeconds: Long,
        val errorMessage: String? = null
    )

    private data class DownloadRequest(
        val modelId: String,
        val modelUrl: String,
        val installKey: String,
        val langKey: String?,
        val sourceLang: String?,
        val switchNow: Boolean
    )

    inner class LocalBinder : Binder() {
        fun getService(): ModelDownloadService = this@ModelDownloadService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeRequests = ConcurrentHashMap<String, DownloadRequest>()
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val downloadProgress = ConcurrentHashMap<String, DownloadProgress>()
    private var progressCallback: ((DownloadProgress) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD, ACTION_RESUME_DOWNLOAD -> {
                val request = intent.toDownloadRequest() ?: return START_NOT_STICKY
                startDownload(request)
            }

            ACTION_PAUSE_DOWNLOAD -> {
                intent.getStringExtra(EXTRA_MODEL_ID)?.let(::pauseDownload)
            }

            ACTION_CANCEL_DOWNLOAD -> {
                intent.getStringExtra(EXTRA_MODEL_ID)?.let(::cancelDownload)
            }
        }
        return START_STICKY
    }

    fun setProgressCallback(callback: (DownloadProgress) -> Unit) {
        progressCallback = callback
        downloadProgress.values
            .sortedBy { it.modelId }
            .forEach(callback)
    }

    fun clearProgressCallback() {
        progressCallback = null
    }

    fun getDownloadProgress(modelId: String): DownloadProgress? = downloadProgress[modelId]

    fun getAllDownloadProgress(): Map<String, DownloadProgress> = downloadProgress.toMap()

    private fun startDownload(request: DownloadRequest) {
        activeRequests[request.modelId] = request
        val currentJob = downloadJobs[request.modelId]
        if (currentJob?.isActive == true) {
            downloadProgress[request.modelId]?.let { progressCallback?.invoke(it) }
            return
        }

        if (!checkNetworkAvailable()) {
            updateProgress(
                modelId = request.modelId,
                status = DownloadStatus.FAILED,
                bytesDownloaded = 0,
                totalBytes = 0,
                percentage = 0,
                speedBps = 0,
                remainingSeconds = 0,
                errorMessage = getString(R.string.error_no_network)
            )
            activeRequests.remove(request.modelId)
            return
        }

        val job = serviceScope.launch {
            try {
                val installedRoot = downloadAndInstall(request)
                persistInstalledModel(request, installedRoot)
                updateProgress(
                    modelId = request.modelId,
                    status = DownloadStatus.COMPLETED,
                    bytesDownloaded = installedRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    totalBytes = installedRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    percentage = 100,
                    speedBps = 0,
                    remainingSeconds = 0
                )
            } catch (e: CancellationException) {
                val currentStatus = downloadProgress[request.modelId]?.status
                if (currentStatus != DownloadStatus.PAUSED && currentStatus != DownloadStatus.CANCELLED) {
                    updateProgress(
                        modelId = request.modelId,
                        status = DownloadStatus.CANCELLED,
                        bytesDownloaded = 0,
                        totalBytes = 0,
                        percentage = 0,
                        speedBps = 0,
                        remainingSeconds = 0
                    )
                }
            } catch (e: Exception) {
                updateProgress(
                    modelId = request.modelId,
                    status = DownloadStatus.FAILED,
                    bytesDownloaded = downloadProgress[request.modelId]?.bytesDownloaded ?: 0,
                    totalBytes = downloadProgress[request.modelId]?.totalBytes ?: 0,
                    percentage = downloadProgress[request.modelId]?.percentage ?: 0,
                    speedBps = 0,
                    remainingSeconds = 0,
                    errorMessage = e.message ?: getString(R.string.error_unknown)
                )
            } finally {
                downloadJobs.remove(request.modelId)
                val status = downloadProgress[request.modelId]?.status
                if (status != DownloadStatus.PAUSED) {
                    activeRequests.remove(request.modelId)
                }
                stopForegroundIfNoActiveDownloads()
            }
        }

        downloadJobs[request.modelId] = job
    }

    private fun pauseDownload(modelId: String) {
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        val current = downloadProgress[modelId] ?: return
        updateProgress(
            modelId = modelId,
            status = DownloadStatus.PAUSED,
            bytesDownloaded = current.bytesDownloaded,
            totalBytes = current.totalBytes,
            percentage = current.percentage,
            speedBps = 0,
            remainingSeconds = 0
        )
    }

    private fun cancelDownload(modelId: String) {
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        val request = activeRequests.remove(modelId)
        request?.let {
            deleteStagingDirs(it.installKey)
        }

        val current = downloadProgress[modelId]
        updateProgress(
            modelId = modelId,
            status = DownloadStatus.CANCELLED,
            bytesDownloaded = current?.bytesDownloaded ?: 0,
            totalBytes = current?.totalBytes ?: 0,
            percentage = current?.percentage ?: 0,
            speedBps = 0,
            remainingSeconds = 0
        )
    }

    private suspend fun downloadAndInstall(request: DownloadRequest): File {
        val root = ModelManager.selectWritableModelRoot(this)
        if (!root.exists() && !root.mkdirs()) {
            throw java.io.IOException(getString(R.string.error_create_model_root, root.absolutePath))
        }

        val staging = File(root, ".${request.installKey}.tmp-download")
        if (!staging.exists() && !staging.mkdirs()) {
            throw java.io.IOException(getString(R.string.error_create_language_dir, staging.absolutePath))
        }

        val partFile = File(staging, "model.zip.part")
        val totalBytes = downloadFileWithResume(request.modelId, request.modelUrl, partFile, root)
        val zipFile = File(staging, "model.zip")
        if (zipFile.exists()) {
            zipFile.delete()
        }
        if (partFile.exists() && !partFile.renameTo(zipFile)) {
            partFile.copyTo(zipFile, overwrite = true)
            partFile.delete()
        }

        updateProgress(
            modelId = request.modelId,
            status = DownloadStatus.EXTRACTING,
            bytesDownloaded = totalBytes,
            totalBytes = totalBytes,
            percentage = 100,
            speedBps = 0,
            remainingSeconds = 0
        )

        val installedRoot = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val extractedRoot = ModelManager.unzipModel(zipFile, staging)
            zipFile.delete()

            updateProgress(
                modelId = request.modelId,
                status = DownloadStatus.VALIDATING,
                bytesDownloaded = totalBytes,
                totalBytes = totalBytes,
                percentage = 100,
                speedBps = 0,
                remainingSeconds = 0
            )

            val resolved = ModelManager.resolveModelRootPublic(extractedRoot)
            val validationError = ModelManager.validateInstalledModel(
                this@ModelDownloadService,
                resolved,
                checkRuntimeSize = false
            )
            if (validationError != null) {
                throw java.io.IOException(validationError)
            }

            ModelManager.removeOtherInstalledCopies(
                ctx = this@ModelDownloadService,
                lang = request.installKey,
                keepRoot = root
            )

            val dest = ModelManager.modelDir(root, request.installKey)
            if (dest.exists() && !dest.deleteRecursively()) {
                throw java.io.IOException(getString(R.string.error_replace_model_dir, dest.absolutePath))
            }
            if (!staging.renameTo(dest)) {
                staging.copyRecursively(dest, overwrite = true)
                staging.deleteRecursively()
            }

            ModelManager.resolveModelRootPublic(dest)
        }

        return installedRoot
    }

    private suspend fun downloadFileWithResume(
        modelId: String,
        url: String,
        outFile: File,
        root: File
    ): Long = withContext(Dispatchers.IO) {
        outFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw java.io.IOException(getString(R.string.error_create_parent_dir, parent.absolutePath))
            }
        }

        val existingBytes = if (outFile.exists()) outFile.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 60_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (existingBytes > 0) {
                setRequestProperty("Range", "bytes=$existingBytes-")
            }
        }

        try {
            conn.connect()
            val responseCode = conn.responseCode
            val supportsResume = responseCode == HttpURLConnection.HTTP_PARTIAL
            val startByte = if (supportsResume) existingBytes else 0L

            if (!supportsResume && existingBytes > 0) {
                outFile.delete()
            }
            if (responseCode !in 200..299 && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw java.io.IOException(getString(R.string.error_download_http, responseCode, url))
            }

            val totalBytes = if (supportsResume) {
                val contentRange = conn.getHeaderField("Content-Range")
                if (!contentRange.isNullOrBlank() && contentRange.contains("/")) {
                    contentRange.substringAfterLast("/").toLongOrNull()
                        ?: (startByte + conn.contentLengthLong.coerceAtLeast(0))
                } else {
                    startByte + conn.contentLengthLong.coerceAtLeast(0)
                }
            } else {
                conn.contentLengthLong.coerceAtLeast(0)
            }

            ensureStorageAvailable(root, totalBytes, startByte)

            RandomAccessFile(outFile, "rw").use { raf ->
                if (supportsResume) {
                    raf.seek(startByte)
                } else {
                    raf.setLength(0)
                }

                conn.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var totalRead = startByte
                    var lastUpdateTime = System.currentTimeMillis()
                    var lastSampleBytes = totalRead
                    val speedSamples = ArrayDeque<Long>()

                    updateProgress(
                        modelId = modelId,
                        status = DownloadStatus.DOWNLOADING,
                        bytesDownloaded = totalRead,
                        totalBytes = totalBytes,
                        percentage = progressPercent(totalRead, totalBytes),
                        speedBps = 0,
                        remainingSeconds = 0
                    )

                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break

                        raf.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val now = System.currentTimeMillis()
                        val elapsedMs = now - lastUpdateTime
                        if (elapsedMs >= 500) {
                            val deltaBytes = totalRead - lastSampleBytes
                            val speedBps = if (elapsedMs > 0) {
                                (deltaBytes * 1000L) / elapsedMs
                            } else {
                                0L
                            }
                            speedSamples.addLast(speedBps)
                            while (speedSamples.size > 5) {
                                speedSamples.removeFirst()
                            }
                            val averageSpeed = if (speedSamples.isEmpty()) {
                                0L
                            } else {
                                speedSamples.average().roundToInt().toLong()
                            }
                            val remainingSeconds = if (averageSpeed > 0 && totalBytes > 0) {
                                ((totalBytes - totalRead).coerceAtLeast(0L)) / averageSpeed
                            } else {
                                0L
                            }
                            updateProgress(
                                modelId = modelId,
                                status = DownloadStatus.DOWNLOADING,
                                bytesDownloaded = totalRead,
                                totalBytes = totalBytes,
                                percentage = progressPercent(totalRead, totalBytes),
                                speedBps = averageSpeed,
                                remainingSeconds = remainingSeconds
                            )
                            lastUpdateTime = now
                            lastSampleBytes = totalRead
                        }
                    }

                    updateProgress(
                        modelId = modelId,
                        status = DownloadStatus.DOWNLOADING,
                        bytesDownloaded = totalRead,
                        totalBytes = totalBytes,
                        percentage = if (totalBytes > 0L) 100 else progressPercent(totalRead, totalBytes),
                        speedBps = 0,
                        remainingSeconds = 0
                    )
                    return@withContext max(totalBytes, totalRead)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun persistInstalledModel(request: DownloadRequest, installedRoot: File) {
        val prefs = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        prefs.edit {
            putString("asr_model_path_${request.modelId}", installedRoot.absolutePath)
            putString("asr_model_url_${request.modelId}", request.modelUrl)
            request.langKey?.let {
                putString("asr_model_for_lang_$it", request.modelId)
                putString("asr_current_lang_key", it)
            }
            request.sourceLang?.let {
                putString("asr_source_lang_for_model_${request.modelId}", it)
            }
            if (request.switchNow) {
                putString("asr_model_id", request.modelId)
                putString("asr_lang", request.modelId)
            }
        }
    }

    private fun updateProgress(
        modelId: String,
        status: DownloadStatus,
        bytesDownloaded: Long,
        totalBytes: Long,
        percentage: Int,
        speedBps: Long,
        remainingSeconds: Long,
        errorMessage: String? = null
    ) {
        val progress = DownloadProgress(
            modelId = modelId,
            status = status,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            percentage = percentage.coerceIn(0, 100),
            speedBytesPerSecond = speedBps.coerceAtLeast(0L),
            remainingTimeSeconds = remainingSeconds.coerceAtLeast(0L),
            errorMessage = errorMessage
        )
        downloadProgress[modelId] = progress
        updateNotification(progress)
        progressCallback?.invoke(progress)
    }

    private fun updateNotification(progress: DownloadProgress) {
        val notification = buildNotification(progress)
        if (progress.status == DownloadStatus.DOWNLOADING ||
            progress.status == DownloadStatus.EXTRACTING ||
            progress.status == DownloadStatus.VALIDATING
        ) {
            startForegroundCompat(notification)
        } else {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(progress: DownloadProgress): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (progress.status) {
            DownloadStatus.DOWNLOADING -> getString(R.string.notif_downloading_model, progress.modelId)
            DownloadStatus.EXTRACTING -> getString(R.string.notif_extracting_model, progress.modelId)
            DownloadStatus.VALIDATING -> getString(R.string.notif_validating_model, progress.modelId)
            DownloadStatus.COMPLETED -> getString(R.string.notif_download_complete, progress.modelId)
            DownloadStatus.FAILED -> getString(R.string.notif_download_failed, progress.modelId)
            DownloadStatus.PAUSED -> getString(R.string.notif_download_paused, progress.modelId)
            DownloadStatus.CANCELLED -> getString(R.string.notif_download_cancelled, progress.modelId)
            DownloadStatus.IDLE -> getString(R.string.notif_model_download)
        }

        val text = when (progress.status) {
            DownloadStatus.DOWNLOADING -> getString(
                R.string.notif_download_progress,
                formatBytes(progress.bytesDownloaded),
                formatBytes(progress.totalBytes),
                formatBytes(progress.speedBytesPerSecond),
                formatRemainingTime(progress.remainingTimeSeconds)
            )

            DownloadStatus.EXTRACTING -> getString(R.string.notif_extracting_model, progress.modelId)
            DownloadStatus.VALIDATING -> getString(R.string.notif_validating_model, progress.modelId)
            DownloadStatus.PAUSED -> getString(R.string.notif_tap_to_resume)
            DownloadStatus.FAILED -> progress.errorMessage ?: getString(R.string.error_unknown)
            DownloadStatus.COMPLETED -> getString(R.string.download_complete, progress.modelId)
            DownloadStatus.CANCELLED -> getString(R.string.notif_download_cancelled, progress.modelId)
            DownloadStatus.IDLE -> ""
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(
                progress.status == DownloadStatus.DOWNLOADING ||
                    progress.status == DownloadStatus.EXTRACTING ||
                    progress.status == DownloadStatus.VALIDATING
            )
            .setProgress(
                100,
                progress.percentage,
                progress.totalBytes <= 0L &&
                    (progress.status == DownloadStatus.DOWNLOADING ||
                        progress.status == DownloadStatus.EXTRACTING ||
                        progress.status == DownloadStatus.VALIDATING)
            )
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundIfNoActiveDownloads() {
        val hasActive = downloadProgress.values.any {
            it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.EXTRACTING ||
                it.status == DownloadStatus.VALIDATING
        }
        if (!hasActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_downloads),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_downloads_desc)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun checkNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun deleteStagingDirs(installKey: String) {
        val roots = linkedSetOf<File>()
        roots.add(ModelManager.modelRoot(this))
        getExternalFilesDir(null)?.let { roots.add(File(it, "vosk_models")) }
        roots.forEach { root ->
            File(root, ".${installKey}.tmp-download").deleteRecursively()
        }
    }

    private fun ensureStorageAvailable(root: File, totalBytes: Long, existingBytes: Long) {
        if (totalBytes <= 0L) return
        val remainingBytes = (totalBytes - existingBytes).coerceAtLeast(0L)
        val unzipHeadroom = totalBytes / 2
        val requiredBytes = remainingBytes + unzipHeadroom + 128L * 1024L * 1024L
        if (root.usableSpace < requiredBytes) {
            throw java.io.IOException(
                getString(
                    R.string.error_not_enough_storage,
                    formatBytes(requiredBytes),
                    formatBytes(root.usableSpace)
                )
            )
        }
    }

    private fun progressPercent(done: Long, total: Long): Int {
        if (total <= 0L) return 0
        return ((done.coerceAtMost(total) * 100.0) / total).roundToInt()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) {
            "${value.toLong()} ${units[index]}"
        } else {
            String.format("%.1f %s", value, units[index])
        }
    }

    private fun formatRemainingTime(seconds: Long): String {
        return when {
            seconds <= 0L -> getString(R.string.time_seconds, 0)
            seconds < 60L -> getString(R.string.time_seconds, seconds)
            seconds < 3600L -> getString(R.string.time_minutes, seconds / 60L)
            else -> getString(R.string.time_hours, seconds / 3600L)
        }
    }

    private fun Intent.toDownloadRequest(): DownloadRequest? {
        val modelId = getStringExtra(EXTRA_MODEL_ID)?.trim().orEmpty()
        val modelUrl = getStringExtra(EXTRA_MODEL_URL)?.trim().orEmpty()
        if (modelId.isBlank() || modelUrl.isBlank()) return null
        val installKey = getStringExtra(EXTRA_MODEL_LANG)?.trim().orEmpty().ifBlank { modelId }
        return DownloadRequest(
            modelId = modelId,
            modelUrl = modelUrl,
            installKey = installKey,
            langKey = getStringExtra(EXTRA_LANG_KEY)?.trim().orEmpty().ifBlank { null },
            sourceLang = getStringExtra(EXTRA_SOURCE_LANG)?.trim().orEmpty().ifBlank { null },
            switchNow = getBooleanExtra(EXTRA_SWITCH_NOW, false)
        )
    }

    override fun onDestroy() {
        downloadJobs.values.forEach { it.cancel() }
        serviceScope.cancel()
        super.onDestroy()
    }
}
