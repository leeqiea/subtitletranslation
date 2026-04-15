@file:Suppress("SameParameterValue")

package com.leaqia.subtitletranslation

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.leaqia.subtitletranslation.model.ModelManager
import com.leaqia.subtitletranslation.service.ModelDownloadService
import com.leaqia.subtitletranslation.service.OverlayService
import com.leaqia.subtitletranslation.util.AsrLanguageResolver
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

/**
 * 主界面负责权限引导、模型管理、翻译配置，以及启动悬浮字幕服务。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SubtitleTranslation/Main"
        private const val VOSK_MODELS_PAGE = "https://alphacephei.com/vosk/models"
        private const val VOSK_MODELS_BASE = "https://alphacephei.com/vosk/models"
        // 这些 SharedPreferences key 会被 Activity 和 Service 共同使用。
        private const val PREF_MODEL_ID = "asr_model_id"
        private const val PREF_TARGET_LANG = "translate_target_lang"
        private const val PREF_DISPLAY_MODE = "subtitle_display_mode"
        private const val DISPLAY_BOTH = "both"
        private const val DISPLAY_TRANSLATION_ONLY = "translation_only"
        private const val PREF_MODEL_FOR_LANG_PREFIX = "asr_model_for_lang_"
        private const val PREF_SMALL_MODEL_ID_PREFIX = "asr_small_model_id_"
        private const val PREF_LANGKEY_FOR_CODE_PREFIX = "asr_langkey_for_code_"
        private const val PREF_LANGKEY_FOR_MODEL_PREFIX = "asr_langkey_for_model_"
        private const val PREF_MODEL_URL_PREFIX = "asr_model_url_"
        private const val PREF_SOURCE_LANG_FOR_MODEL_PREFIX = "asr_source_lang_for_model_"
        private const val PREF_CURRENT_LANG_KEY = "asr_current_lang_key"
        private const val PREF_MODEL_META_TS = "asr_model_meta_ts"
        private const val PREF_TRANSLATE_DOWNLOAD_PENDING_PREFIX = "translate_dl_pending_"
        private const val PREF_TRANSLATE_DOWNLOAD_TS_PREFIX = "translate_dl_ts_"
        private const val TRANSLATE_DOWNLOAD_PENDING_TTL_MS = 2L * 60 * 60 * 1000
        private const val OVERLAY_SERVICE_CLASS_SUFFIX = ".service.OverlayService"
        private const val EXTRA_OVERLAY_RESULT_CODE = "result_code"
        private const val EXTRA_OVERLAY_RESULT_DATA = "result_data"
    }

    // 从 Vosk 页面解析出来的一条模型元数据。
    private data class ModelInfo(
        val id: String,
        val lang: String?,
        val sizeLabel: String,
        val sizeBytes: Long?,
        val url: String
    )

    // 语言维度的聚合结果，用于在弹窗里按语种展示模型。
    private data class LangItem(
        val lang: String,
        val langKey: String,
        val displayName: String,
        val models: List<ModelInfo>,
        val smallest: ModelInfo
    )

    // 用来在用户跳转系统授权页后，回到应用时继续未完成的流程。
    private var pendingStartCapture = false
    private var pendingRecordForCapture = false
    private var askedOverlayOnEntry = false
    private var askedRecordOnEntry = false
    // 主界面的状态展示控件。
    private lateinit var tvModelStatus: TextView
    private lateinit var tvTargetLang: TextView
    private lateinit var tvTranslateStatus: TextView
    private lateinit var tvDisplayMode: TextView
    // 网络模型列表做内存缓存，避免短时间内重复下载和解析。
    private var cachedModelList: List<ModelInfo>? = null
    private var settingsPrefs: SharedPreferences? = null
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val lastDownloadStatuses = mutableMapOf<String, ModelDownloadService.DownloadStatus>()

    // 下载服务绑定
    private var downloadService: ModelDownloadService? = null
    private var downloadServiceBound = false

    private val downloadServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as ModelDownloadService.LocalBinder
            downloadService = binder.getService()
            downloadServiceBound = true
            downloadService?.setProgressCallback { progress ->
                runOnUiThread { updateDownloadProgress(progress) }
            }
            downloadService?.getAllDownloadProgress()
                ?.values
                ?.sortedBy { it.modelId }
                ?.forEach { progress ->
                    runOnUiThread { updateDownloadProgress(progress) }
                }
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            downloadServiceBound = false
            downloadService = null
        }
    }

    // 申请 MediaProjection 授权（弹系统弹窗）
    private val mpLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val i = overlayServiceIntent(OverlayService.ACTION_START_CAPTURE).apply {
                putExtra(EXTRA_OVERLAY_RESULT_CODE, res.resultCode)
                putExtra(EXTRA_OVERLAY_RESULT_DATA, res.data)
            }
            startServiceCompat(i)
        } else {
            toast(getString(R.string.toast_capture_auth_cancelled))
        }
    }

    // 申请 RECORD_AUDIO 运行时权限（AudioRecord 需要）
    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (pendingRecordForCapture) {
                pendingRecordForCapture = false
                requestMediaProjection()
            }
        } else {
            toast(getString(R.string.toast_need_record_permission))
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 让底部内容避开导航栏，避免按钮被遮住。
        applyBottomInsetForNavigationBar()

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        installSettingsDebugLogs(prefs)

        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvTargetLang = findViewById(R.id.tvTargetLang)
        tvTranslateStatus = findViewById(R.id.tvTranslateStatus)
        tvDisplayMode = findViewById(R.id.tvDisplayMode)

        updateModelStatus()
        updateTargetLangUi()
        updateDisplayModeUi()
        ensureModelMetadataCachedAsync()

        // Bind to download service
        Intent(this, ModelDownloadService::class.java).also { intent ->
            bindService(intent, downloadServiceConnection, Context.BIND_AUTO_CREATE)
        }

        findViewById<Button>(R.id.btnSelectTargetLang).setOnClickListener {
            showTargetLanguageDialog()
        }

        findViewById<Button>(R.id.btnDisplayMode).setOnClickListener {
            toggleDisplayMode()
        }

        findViewById<Button>(R.id.btnCapture).setOnClickListener {
            val modelId = currentModelId(prefs)
            if (modelId.isNullOrBlank()) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_model_not_selected),
                    Toast.LENGTH_SHORT
                ).show()
            }

            // 先确保悬浮窗权限，否则跳设置页，回到界面后继续
            if (!hasOverlayPermission()) {
                pendingStartCapture = true
                ensureOverlayPermission()
                return@setOnClickListener
            }

            pendingStartCapture = false
            ensureRecordAudioThenProjection()
        }

        findViewById<Button>(R.id.btnDownloadModel).setOnClickListener {
            showModelListDialog()
        }
    }

    // 动态给 ScrollView 增加底部 inset，兼容手势导航和三键导航。
    private fun applyBottomInsetForNavigationBar() {
        val scrollView = findViewById<ScrollView>(R.id.mainScrollView)
        val initialPaddingLeft = scrollView.paddingLeft
        val initialPaddingTop = scrollView.paddingTop
        val initialPaddingRight = scrollView.paddingRight
        val initialPaddingBottom = scrollView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val navigationBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                initialPaddingLeft,
                initialPaddingTop,
                initialPaddingRight,
                initialPaddingBottom + navigationBarInsets.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(scrollView)
    }

    // 从系统设置或授权页返回时，在这里续上之前等待的动作。
    override fun onResume() {
        super.onResume()
        ensurePermissionsOnEntry()
        if (pendingStartCapture && hasOverlayPermission()) {
            pendingStartCapture = false
            ensureRecordAudioThenProjection()
        }
    }

    override fun onDestroy() {
        uninstallSettingsDebugLogs()
        if (downloadServiceBound) {
            downloadService?.clearProgressCallback()
            unbindService(downloadServiceConnection)
            downloadServiceBound = false
        }
        super.onDestroy()
    }

    // 悬浮窗权限是 OverlayService 能否显示字幕层的前提。
    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    // 缺权限就跳到系统授权页，真正继续流程留给 onResume 处理。
    private fun ensureOverlayPermission() {
        if (!hasOverlayPermission()) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()
                )
            )
            return
        }
    }

    // 录音权限是系统音频采集和麦克风采集的共同前提。
    private fun ensureRecordAudioThenProjection() {
        val granted = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        if (granted) {
            requestMediaProjection()
        } else {
            pendingRecordForCapture = true
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 弹出 MediaProjection 授权页，把系统音频捕获授权交给系统处理。
    private fun requestMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mpLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // 应用恢复到前台时，主动把缺失权限补齐，减少按钮点击后的打断感。
    private fun ensurePermissionsOnEntry() {
        if (!hasOverlayPermission()) {
            if (!askedOverlayOnEntry) {
                askedOverlayOnEntry = true
                ensureOverlayPermission()
            }
            return
        }
        val hasRecord = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasRecord && !askedRecordOnEntry) {
            askedRecordOnEntry = true
            pendingRecordForCapture = false
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 模型列表更新频率低，所以只在缓存过期后后台刷新一次。
    private fun ensureModelMetadataCachedAsync() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val lastTs = prefs.getLong(PREF_MODEL_META_TS, 0L)
        val now = System.currentTimeMillis()
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        if (now - lastTs < sevenDays) return
        Thread {
            try {
                val html = downloadText(VOSK_MODELS_PAGE)
                val models = parseModelList(html)
                cachedModelList = models
                cacheModelMetadata(models)
                prefs.edit { putLong(PREF_MODEL_META_TS, System.currentTimeMillis()) }
            } catch (_: Exception) {
            }
        }.start()
    }

    // 不同系统对前台服务启动限制不同，这里做一次兼容兜底。
    private fun startServiceCompat(i: Intent) {
        try {
            // 应用前台场景优先用 startService，避免某些机型对 startForegroundService 的额外限制
            startService(i)
        } catch (e: Exception) {
            try {
                startForegroundService(i)
            } catch (e2: Exception) {
                toast(
                    getString(
                        R.string.toast_start_service_failed,
                        e2.message ?: e.message ?: getString(R.string.error_unknown)
                    )
                )
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun overlayServiceIntent(action: String): Intent {
        return Intent().setClassName(this, packageName + OVERLAY_SERVICE_CLASS_SUFFIX).apply {
            this.action = action
        }
    }

    private fun currentModelId(prefs: SharedPreferences): String? {
        return prefs.getString(PREF_MODEL_ID, null) ?: prefs.getString("asr_lang", null)
    }

    // 主界面状态文案依赖于“当前模型是否存在且通过校验”。
    private fun updateModelStatus() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val modelId = currentModelId(prefs)
        tvModelStatus.text = when {
            modelId.isNullOrBlank() -> getString(R.string.model_status_not_selected)
            ModelManager.isInstalled(this, modelId) ->
                getString(R.string.model_status_installed, modelId)

            else -> getString(R.string.model_status_not_installed, modelId)
        }
    }

    // 目标语言必须是 ML Kit 支持的代码；旧值非法时自动回退。
    private fun currentTargetLang(prefs: SharedPreferences): String {
        val saved = prefs.getString(PREF_TARGET_LANG, null)
        val all = TranslateLanguage.getAllLanguages()
        if (saved != null && all.contains(saved)) {
            Log.d(TAG, "currentTargetLang: using saved value=$saved")
            return saved
        }
        if (!saved.isNullOrBlank()) {
            Log.w(TAG, "currentTargetLang: invalid saved value=$saved, fallback required")
        }
        val fallback = if (all.contains(TranslateLanguage.CHINESE)) {
            TranslateLanguage.CHINESE
        } else {
            all.first()
        }
        Log.w(TAG, "currentTargetLang: fallback=$fallback, hasChinese=${all.contains(TranslateLanguage.CHINESE)}")
        return fallback
    }

    // 刷新目标语言文案，并同步查询离线翻译模型状态。
    @SuppressLint("SetTextI18n")
    private fun updateTargetLangUi() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val lang = currentTargetLang(prefs)
        val name = displayLangName(lang)
        Log.i(TAG, "updateTargetLangUi: target=$lang, name=$name")
        tvTargetLang.text = getString(R.string.target_language_format, name, lang)
        refreshTranslateModelStatus(lang)
    }

    private fun currentDisplayMode(prefs: SharedPreferences): String {
        val saved = prefs.getString(PREF_DISPLAY_MODE, null)
        return if (saved == DISPLAY_TRANSLATION_ONLY) DISPLAY_TRANSLATION_ONLY else DISPLAY_BOTH
    }

    // 把内部显示模式枚举转换成面向用户的文案。
    @SuppressLint("SetTextI18n")
    private fun updateDisplayModeUi() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val mode = currentDisplayMode(prefs)
        val label = if (mode == DISPLAY_TRANSLATION_ONLY) {
            getString(R.string.display_mode_translation_only)
        } else {
            getString(R.string.display_mode_both)
        }
        tvDisplayMode.text = getString(R.string.display_mode_format, label)
    }

    // 模式切换后立即通知悬浮窗刷新，避免界面与设置不同步。
    private fun toggleDisplayMode() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val current = currentDisplayMode(prefs)
        val next = if (current == DISPLAY_TRANSLATION_ONLY) DISPLAY_BOTH else DISPLAY_TRANSLATION_ONLY
        prefs.edit { putString(PREF_DISPLAY_MODE, next) }
        updateDisplayModeUi()
        if (!hasOverlayPermission()) return
        val i = overlayServiceIntent(OverlayService.ACTION_UPDATE)
        startServiceCompat(i)
    }

    // 允许用户直接切换翻译目标语言，并在选择后提前下载离线模型。
    private fun showTargetLanguageDialog() {
        val all = TranslateLanguage.getAllLanguages().toList()
        val items = all
            .map { it to "${displayLangName(it)} ($it)" }
            .sortedBy { it.second }

        val labels = items.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_select_target_language_title)
            .setItems(labels) { _, which ->
                val selected = items[which].first
                val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                val previous = prefs.getString(PREF_TARGET_LANG, null)
                Log.i(TAG, "showTargetLanguageDialog: user selected target $previous -> $selected")
                prefs.edit { putString(PREF_TARGET_LANG, selected) }
                updateTargetLangUi()
                downloadTargetModelIfNeeded(selected)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // 只监听关键设置项，方便排查谁改了翻译目标语言。
    private fun installSettingsDebugLogs(prefs: SharedPreferences) {
        settingsPrefs = prefs
        settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == PREF_TARGET_LANG) {
                val value = sp.getString(PREF_TARGET_LANG, null)
                Log.w(TAG, "settings changed: $PREF_TARGET_LANG=$value")
            }
        }
        settingsListener?.let { prefs.registerOnSharedPreferenceChangeListener(it) }
        Log.i(TAG, "initial settings: $PREF_TARGET_LANG=${prefs.getString(PREF_TARGET_LANG, null)}")
    }

    // Activity 退出时取消监听，防止泄漏旧的上下文。
    private fun uninstallSettingsDebugLogs() {
        val prefs = settingsPrefs
        val listener = settingsListener
        if (prefs != null && listener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
        settingsPrefs = null
        settingsListener = null
    }

    // 预下载目标语言模型，降低第一次真正翻译时的等待成本。
    @SuppressLint("SetTextI18n")
    private fun downloadTargetModelIfNeeded(lang: String) {
        val model = TranslateRemoteModel.Builder(lang).build()
        val conditions = DownloadConditions.Builder().build()
        markTranslateModelDownloading(lang, true)
        tvTranslateStatus.text = getString(R.string.translate_model_downloading, displayLangName(lang))
        RemoteModelManager.getInstance()
            .download(model, conditions)
            .addOnSuccessListener {
                markTranslateModelDownloading(lang, false)
                tvTranslateStatus.text = getString(R.string.translate_model_ready, displayLangName(lang))
            }
            .addOnFailureListener { e ->
                markTranslateModelDownloading(lang, false)
                tvTranslateStatus.text = getString(
                    R.string.translate_model_download_failed,
                    e.message ?: getString(R.string.error_unknown)
                )
            }
    }

    // 结合本地已下载状态和“最近在下载”的临时标记来显示翻译模型状态。
    @SuppressLint("SetTextI18n")
    private fun refreshTranslateModelStatus(lang: String) {
        val pending = isTranslateModelDownloading(lang)
        if (pending) {
            tvTranslateStatus.text = getString(R.string.translate_model_downloading, displayLangName(lang))
        }
        RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val has = models.any { it.language == lang }
                tvTranslateStatus.text = when {
                    has -> {
                        markTranslateModelDownloading(lang, false)
                        getString(R.string.translate_model_ready, displayLangName(lang))
                    }

                    isTranslateModelDownloading(lang) ->
                        getString(R.string.translate_model_downloading, displayLangName(lang))

                    else ->
                        getString(R.string.translate_model_not_downloaded, displayLangName(lang))
                }
            }
            .addOnFailureListener {
                tvTranslateStatus.text = if (isTranslateModelDownloading(lang)) {
                    getString(R.string.translate_model_downloading, displayLangName(lang))
                } else {
                    getString(R.string.translate_model_status_unknown)
                }
            }
    }

    // 下载请求是异步的，这里额外记一个 pending 标志给 UI 使用。
    private fun markTranslateModelDownloading(lang: String, downloading: Boolean) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.edit {
            putBoolean(PREF_TRANSLATE_DOWNLOAD_PENDING_PREFIX + lang, downloading)
            if (downloading) {
                putLong(PREF_TRANSLATE_DOWNLOAD_TS_PREFIX + lang, System.currentTimeMillis())
            } else {
                remove(PREF_TRANSLATE_DOWNLOAD_TS_PREFIX + lang)
            }
        }
    }

    // pending 状态带 TTL，避免异常退出后界面永远卡在“下载中”。
    private fun isTranslateModelDownloading(lang: String): Boolean {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val pending = prefs.getBoolean(PREF_TRANSLATE_DOWNLOAD_PENDING_PREFIX + lang, false)
        if (!pending) return false
        val ts = prefs.getLong(PREF_TRANSLATE_DOWNLOAD_TS_PREFIX + lang, 0L)
        val fresh = ts > 0L && System.currentTimeMillis() - ts <= TRANSLATE_DOWNLOAD_PENDING_TTL_MS
        if (!fresh) {
            markTranslateModelDownloading(lang, false)
            return false
        }
        return true
    }

    // 模型列表弹窗既支持官方模型，也支持用户输入自定义 zip 地址。
    @SuppressLint("SetTextI18n")
    private fun showModelListDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_model_list, null)
        val progress = view.findViewById<ProgressBar>(R.id.progressLoading)
        val status = view.findViewById<TextView>(R.id.tvLoadingStatus)
        val container = view.findViewById<LinearLayout>(R.id.modelListContainer)
        val etCustomName = view.findViewById<EditText>(R.id.etCustomModelName)
        val etCustomUrl = view.findViewById<EditText>(R.id.etCustomModelUrl)
        val btnCustomDownload = view.findViewById<Button>(R.id.btnCustomDownload)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton(R.string.dialog_close, null)
            .create()

        dialog.show()

        btnCustomDownload.setOnClickListener {
            val url = etCustomUrl.text.toString().trim()
            if (url.isBlank()) {
                toast(getString(R.string.toast_enter_model_url))
                return@setOnClickListener
            }
            val nameInput = etCustomName.text.toString().trim()
            val modelId = nameInput.ifBlank { deriveModelIdFromUrl(url) }
            dialog.dismiss()
            startCustomModelDownload(modelId, url)
        }

        val cached = cachedModelList
        if (cached != null) {
            progress.visibility = View.GONE
            status.visibility = View.GONE
            cacheModelMetadata(cached)
            populateLanguageButtons(container, buildLangItems(cached), dialog)
            return
        }

        Thread {
            try {
                val html = downloadText(VOSK_MODELS_PAGE)
                val models = parseModelList(html)
                cachedModelList = models
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.visibility = View.GONE
                    cacheModelMetadata(models)
                    populateLanguageButtons(container, buildLangItems(models), dialog)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = getString(
                        R.string.status_load_failed,
                        e.message ?: getString(R.string.error_unknown)
                    )
                }
            }
        }.start()
    }

    private fun populateLanguageButtons(
        container: LinearLayout,
        items: List<LangItem>,
        dialog: AlertDialog
    ) {
        // 每个语种先展示一个入口按钮，具体型号放到下一层弹窗里。
        container.removeAllViews()
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.status_no_available_models)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 14f
            }
            container.addView(empty)
            return
        }

        val margin = dp(8)
        for (item in items) {
            val btn = Button(this).apply {
                isAllCaps = false
                text = item.displayName
                setBackgroundResource(R.drawable.bg_button_secondary)
                backgroundTintList = null
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.button_secondary_text))
                textSize = 15f
                minHeight = dp(48)
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = margin
            btn.layoutParams = lp
            btn.setOnClickListener {
                showModelSelectDialog(item, dialog)
            }
            container.addView(btn)
        }
    }

    // 同一语种下可能有 small / big / graph 等不同版本，这里让用户二次选择。
    @SuppressLint("SetTextI18n")
    private fun showModelSelectDialog(item: LangItem, parent: AlertDialog) {
        val models = item.models
        val labels = models.map { m ->
            val size = if (m.sizeLabel.isNotBlank()) {
                getString(R.string.model_option_size, m.sizeLabel)
            } else {
                ""
            }
            val installed = if (ModelManager.isInstalled(this, m.id)) {
                getString(R.string.model_option_installed_suffix)
            } else {
                ""
            }
            buildString {
                append(m.id)
                if (size.isNotBlank()) append(' ').append(size)
                if (installed.isNotBlank()) append(' ').append(installed)
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_select_model_title, item.displayName))
            .setItems(labels) { _, which ->
                parent.dismiss()
                selectModelForLanguage(item, models[which])
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // 选择模型后优先复用本地已安装模型，否则再走下载流程。
    @SuppressLint("SetTextI18n")
    private fun selectModelForLanguage(item: LangItem, model: ModelInfo) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val langKey = item.langKey
        val sourceLang = resolveSourceLangForModel(model)
        prefs.edit {
            putString(PREF_MODEL_FOR_LANG_PREFIX + langKey, model.id)
            putString(PREF_CURRENT_LANG_KEY, langKey)
            if (!sourceLang.isNullOrBlank()) {
                putString(PREF_SOURCE_LANG_FOR_MODEL_PREFIX + model.id, sourceLang)
            }
        }
        val shouldSwitchNow = true

        if (ModelManager.isInstalled(this, model.id)) {
            val savedPath = prefs.getString("asr_model_path_${model.id}", null)
            val resolved = when {
                !savedPath.isNullOrBlank() && java.io.File(savedPath).exists() ->
                    java.io.File(savedPath)

                else -> ModelManager.resolveInstalledPath(this, model.id)
            }
            if (resolved != null && resolved.exists()) {
                prefs.edit {
                    putString("asr_model_path_${model.id}", resolved.absolutePath)
                    if (!sourceLang.isNullOrBlank()) {
                        putString(PREF_SOURCE_LANG_FOR_MODEL_PREFIX + model.id, sourceLang)
                    }
                    if (shouldSwitchNow) {
                        putString(PREF_MODEL_ID, model.id)
                            .putString("asr_lang", model.id) // 兼容旧逻辑
                    }
                }
                tvModelStatus.text = getString(R.string.model_set, model.id)
                return
            }
        }

        startModelDownloadForLanguage(item, model, shouldSwitchNow)
    }

    // 官方模型下载完成后会写回路径、模型 id 和推断出的源语言。
    @SuppressLint("SetTextI18n")
    private fun startModelDownloadForLanguage(
        item: LangItem,
        model: ModelInfo,
        switchNow: Boolean
    ) {
        val sourceLang = resolveSourceLangForModel(model)
        enqueueModelDownload(
            modelId = model.id,
            modelUrl = model.url,
            installKey = model.id,
            langKey = item.langKey,
            sourceLang = sourceLang,
            switchNow = switchNow
        )
    }

    // 自定义模型下载逻辑和官方模型基本一致，只是少了官网元数据这一层。
    @SuppressLint("SetTextI18n")
    private fun startCustomModelDownload(modelId: String, url: String) {
        val sourceLang = AsrLanguageResolver.resolveTranslateSourceLanguage(modelId)
        enqueueModelDownload(
            modelId = modelId,
            modelUrl = url,
            installKey = modelId,
            langKey = null,
            sourceLang = sourceLang,
            switchNow = true
        )
    }

    @SuppressLint("SetTextI18n")
    private fun enqueueModelDownload(
        modelId: String,
        modelUrl: String,
        installKey: String,
        langKey: String?,
        sourceLang: String?,
        switchNow: Boolean
    ) {
        lastDownloadStatuses.remove(modelId)
        tvModelStatus.text = getString(R.string.download_preparing, modelId)
        val intent = Intent(this, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_START_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, modelId)
            putExtra(ModelDownloadService.EXTRA_MODEL_URL, modelUrl)
            putExtra(ModelDownloadService.EXTRA_MODEL_LANG, installKey)
            putExtra(ModelDownloadService.EXTRA_SWITCH_NOW, switchNow)
            if (!langKey.isNullOrBlank()) {
                putExtra(ModelDownloadService.EXTRA_LANG_KEY, langKey)
            }
            if (!sourceLang.isNullOrBlank()) {
                putExtra(ModelDownloadService.EXTRA_SOURCE_LANG, sourceLang)
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    @SuppressLint("SetTextI18n")
    private fun updateDownloadProgress(progress: ModelDownloadService.DownloadProgress) {
        val previousStatus = lastDownloadStatuses.put(progress.modelId, progress.status)
        tvModelStatus.text = when (progress.status) {
            ModelDownloadService.DownloadStatus.DOWNLOADING -> {
                if (progress.totalBytes > 0) {
                    getString(
                        R.string.download_progress_large,
                        progress.modelId,
                        progress.percentage,
                        formatBytes(progress.bytesDownloaded),
                        formatBytes(progress.totalBytes),
                        formatSpeed(progress.speedBytesPerSecond),
                        formatEta(progress.remainingTimeSeconds)
                    )
                } else {
                    getString(R.string.download_progress, progress.percentage, progress.modelId)
                }
            }

            ModelDownloadService.DownloadStatus.EXTRACTING ->
                getString(R.string.download_extracting, progress.modelId)

            ModelDownloadService.DownloadStatus.VALIDATING ->
                getString(R.string.download_validating, progress.modelId)

            ModelDownloadService.DownloadStatus.PAUSED ->
                getString(R.string.download_paused, progress.modelId)

            ModelDownloadService.DownloadStatus.CANCELLED ->
                getString(R.string.download_cancelled, progress.modelId)

            ModelDownloadService.DownloadStatus.FAILED ->
                getString(
                    R.string.download_failed,
                    progress.errorMessage ?: getString(R.string.error_unknown)
                )

            ModelDownloadService.DownloadStatus.COMPLETED -> {
                updateModelStatus()
                if (previousStatus != ModelDownloadService.DownloadStatus.COMPLETED) {
                    ModelManager.resolveInstalledPath(this, progress.modelId)
                        ?.let { ModelManager.getLargeModelWarning(this, it) }
                        ?.let(::toast)
                }
                getString(R.string.download_complete, progress.modelId)
            }

            ModelDownloadService.DownloadStatus.IDLE ->
                getString(R.string.model_status_unchecked)
        }
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

    private fun formatSpeed(bytesPerSecond: Long): String {
        return "${formatBytes(bytesPerSecond)}/s"
    }

    private fun formatEta(seconds: Long): String {
        return when {
            seconds <= 0L -> getString(R.string.time_seconds, 0)
            seconds < 60L -> getString(R.string.time_seconds, seconds)
            seconds < 3600L -> getString(R.string.time_minutes, seconds / 60L)
            else -> getString(R.string.time_hours, seconds / 3600L)
        }
    }

    // 直接从 Vosk 官网 HTML 表格里抽取模型 id、语言、大小和下载链接。
    private fun parseModelList(html: String): List<ModelInfo> {
        val rowRegex = Regex(
            "<tr[^>]*>.*?</tr>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val tdRegex = Regex(
            "<td[^>]*>(.*?)</td>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val linkRegex = Regex("<a[^>]+href=\"([^\"]+\\.zip)\"[^>]*>([^<]+)</a>", RegexOption.IGNORE_CASE)

        var currentLang: String? = null
        val list = mutableListOf<ModelInfo>()

        for (rowMatch in rowRegex.findAll(html)) {
            val row = rowMatch.value
            val cells = tdRegex.findAll(row).map { stripTags(it.groupValues[1]) }.toList()

            // Vosk 页面里语种标题和模型行混在同一个表格里，这里先识别“语言标题行”。
            val isLangRow = cells.isNotEmpty() &&
                    row.contains("<strong", ignoreCase = true) &&
                    cells.drop(1).all { it.isBlank() }
            if (isLangRow) {
                currentLang = cells[0].trim()
                continue
            }

            val link = linkRegex.find(row) ?: continue
            val href = link.groupValues[1].trim()
            val id = stripTags(link.groupValues[2]).trim()
            if (!id.startsWith("vosk-model")) continue
            if (id.contains("spk")) continue

            val size = if (cells.size > 1) cells[1].trim() else ""
            val sizeBytes = parseSizeToBytes(size)

            val url = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> "https://alphacephei.com$href"
                else -> "$VOSK_MODELS_BASE/$href"
            }
            list.add(
                ModelInfo(
                    id = id,
                    lang = currentLang,
                    sizeLabel = size,
                    sizeBytes = sizeBytes,
                    url = url
                )
            )
        }
        return list.distinctBy { it.id }.sortedBy { it.id }
    }

    // 只做简单 GET，用于拉取官网模型页面。
    @Suppress("SameParameterValue")
    private fun downloadText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) {
            throw java.io.IOException(getString(R.string.http_error_code, code))
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    // 官网表格内容带了少量 HTML 标签和实体，这里先做最小清洗。
    private fun stripTags(html: String): String {
        return html
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace("&amp;", "&")
            .replace(Regex("<[^>]+>"), "")
            .trim()
    }

    // 把官网英文语种名尽量映射成当前系统语言下的显示名称。
    private fun localizeLangName(englishName: String?): String? {
        if (englishName.isNullOrBlank()) return null
        val normalized = englishName.trim()
        if (normalized.equals("Unknown", ignoreCase = true)) return getString(R.string.lang_unknown)
        val all = TranslateLanguage.getAllLanguages()
        val uiLocale = resources.configuration.locales[0] ?: Locale.getDefault()
        for (code in all) {
            val locale = Locale.forLanguageTag(code)
            val enName = locale.getDisplayName(Locale.ENGLISH)
            if (enName.equals(normalized, ignoreCase = true)) {
                val displayName = locale.getDisplayName(uiLocale)
                if (!displayName.isNullOrBlank()) return displayName
            }
        }
        return normalized
    }

    // 用稳定 key 存语种分组，避免直接拿带空格的名字做偏好键值。
    private fun normalizeLangKey(name: String): String {
        return name.lowercase(Locale.ROOT)
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    // 语种名称比较前先做归一化，降低空格和括号差异的影响。
    private fun normalizeLangDisplay(name: String): String {
        return name.lowercase(Locale.ENGLISH)
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    // 尝试把官网语言名映射到 ML Kit 语言代码，找不到时保留少量兜底逻辑。
    private fun findLangCodeForName(englishName: String): String? {
        val target = normalizeLangDisplay(englishName)
        val all = TranslateLanguage.getAllLanguages()
        var fallback: String? = null
        for (code in all) {
            val locale = Locale.forLanguageTag(code)
            val display = normalizeLangDisplay(locale.getDisplayName(Locale.ENGLISH))
            if (display == target) return code
            if (display.startsWith(target) || target.startsWith(display)) {
                if (fallback == null) fallback = code
            }
        }
        if (fallback != null) return fallback
        if (target.contains("chinese")) return TranslateLanguage.CHINESE
        return null
    }

    // 把模型 URL、语种分组和最小模型这些派生信息提前缓存到 SharedPreferences。
    private fun cacheModelMetadata(models: List<ModelInfo>) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.edit {

            for (m in models) {
                val langKey = normalizeLangKey(m.lang ?: "Unknown")
                val sourceLang = resolveSourceLangForModel(m)
                putString(PREF_MODEL_URL_PREFIX + m.id, m.url)
                putString(PREF_LANGKEY_FOR_MODEL_PREFIX + m.id, langKey)
                if (!sourceLang.isNullOrBlank()) {
                    putString(PREF_SOURCE_LANG_FOR_MODEL_PREFIX + m.id, sourceLang)
                }
            }

            val grouped = models.groupBy { it.lang ?: "Unknown" }
            for ((lang, list) in grouped) {
                val langKey = normalizeLangKey(lang)
                val smallest = list.minWithOrNull(
                    compareBy<ModelInfo> { it.sizeBytes ?: Long.MAX_VALUE }.thenBy { it.id }
                )?.id
                if (!smallest.isNullOrBlank()) {
                    putString(PREF_SMALL_MODEL_ID_PREFIX + langKey, smallest)
                }
                val code = findLangCodeForName(lang)
                if (!code.isNullOrBlank()) {
                    putString(PREF_LANGKEY_FOR_CODE_PREFIX + code, langKey)
                    putString(PREF_LANGKEY_FOR_CODE_PREFIX + code.substringBefore('-'), langKey)
                }
            }
        }
    }

    // 优先使用官网的语言列推断源语言，缺失时再回退到模型名启发式解析。
    private fun resolveSourceLangForModel(model: ModelInfo): String? {
        return model.lang?.let(::findLangCodeForName)
            ?: AsrLanguageResolver.resolveTranslateSourceLanguage(model.id)
    }

    // 按语言聚合模型，并为每个语种挑出体积最小的版本作为默认展示信息。
    private fun buildLangItems(models: List<ModelInfo>): List<LangItem> {
        val grouped = models.groupBy { it.lang ?: "Unknown" }
        val items = mutableListOf<LangItem>()
        for ((lang, list) in grouped) {
            val sorted = list.sortedWith(
                compareBy<ModelInfo> { it.sizeBytes ?: Long.MAX_VALUE }.thenBy { it.id }
            )
            val smallest = sorted.firstOrNull() ?: continue
            val displayLang = localizeLangName(lang) ?: lang
            val display = if (smallest.sizeLabel.isNotBlank()) {
                "${displayLang.trim()} (${smallest.sizeLabel})"
            } else {
                displayLang.trim()
            }
            items.add(
                LangItem(
                    lang = lang,
                    langKey = normalizeLangKey(lang),
                    displayName = display,
                    models = sorted,
                    smallest = smallest
                )
            )
        }
        return items.sortedBy { it.displayName }
    }

    // 解析官网类似 “1.8G / 42M” 的尺寸字符串，方便排序挑选小模型。
    private fun parseSizeToBytes(size: String): Long? {
        val m = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([KMG])", RegexOption.IGNORE_CASE)
            .find(size) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].uppercase()
        val mult = when (unit) {
            "K" -> 1024.0
            "M" -> 1024.0 * 1024.0
            "G" -> 1024.0 * 1024.0 * 1024.0
            else -> return null
        }
        return (value * mult).toLong()
    }

    // 自定义下载没有模型 id 时，从 zip 地址推一个尽量稳定的名字。
    private fun deriveModelIdFromUrl(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val name = clean.substringAfterLast('/')
        val base = if (name.endsWith(".zip")) name.dropLast(4) else name
        return base.ifBlank { "custom-model-${System.currentTimeMillis()}" }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // 语言代码最终还是要给用户看，所以这里统一转换成本地化语言名。
    private fun displayLangName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        val uiLocale = resources.configuration.locales[0] ?: Locale.getDefault()
        val name = locale.getDisplayName(uiLocale)
        return if (name.isNullOrBlank()) tag else name
    }
}
