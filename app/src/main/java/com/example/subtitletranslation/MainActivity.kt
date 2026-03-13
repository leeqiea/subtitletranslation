@file:Suppress("SameParameterValue")

package com.example.subtitletranslation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
import com.example.subtitletranslation.model.ModelManager
import com.example.subtitletranslation.service.OverlayService
import com.example.subtitletranslation.util.AsrLanguageResolver
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SubtitleTranslation/Main"
        private const val VOSK_MODELS_PAGE = "https://alphacephei.com/vosk/models"
        private const val VOSK_MODELS_BASE = "https://alphacephei.com/vosk/models"
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
    }

    private data class ModelInfo(
        val id: String,
        val lang: String?,
        val sizeLabel: String,
        val sizeBytes: Long?,
        val url: String
    )

    private data class LangItem(
        val lang: String,
        val langKey: String,
        val displayName: String,
        val models: List<ModelInfo>,
        val smallest: ModelInfo
    )

    private var pendingStartCapture = false
    private var pendingRecordForCapture = false
    private var askedOverlayOnEntry = false
    private var askedRecordOnEntry = false
    private lateinit var tvModelStatus: TextView
    private lateinit var tvTargetLang: TextView
    private lateinit var tvTranslateStatus: TextView
    private lateinit var tvDisplayMode: TextView
    private var cachedModelList: List<ModelInfo>? = null
    private var settingsPrefs: SharedPreferences? = null
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // 申请 MediaProjection 授权（弹系统弹窗）
    private val mpLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val i = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START_CAPTURE
                putExtra(OverlayService.EXTRA_RESULT_CODE, res.resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, res.data)
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
        super.onDestroy()
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

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

    private fun requestMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mpLauncher.launch(mpm.createScreenCaptureIntent())
    }

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

    private fun currentModelId(prefs: SharedPreferences): String? {
        return prefs.getString(PREF_MODEL_ID, null) ?: prefs.getString("asr_lang", null)
    }

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

    private fun toggleDisplayMode() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val current = currentDisplayMode(prefs)
        val next = if (current == DISPLAY_TRANSLATION_ONLY) DISPLAY_BOTH else DISPLAY_TRANSLATION_ONLY
        prefs.edit { putString(PREF_DISPLAY_MODE, next) }
        updateDisplayModeUi()
        if (!hasOverlayPermission()) return
        val i = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE
        }
        startServiceCompat(i)
    }

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

    private fun uninstallSettingsDebugLogs() {
        val prefs = settingsPrefs
        val listener = settingsListener
        if (prefs != null && listener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
        settingsPrefs = null
        settingsListener = null
    }

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

    @SuppressLint("SetTextI18n")
    private fun startModelDownloadForLanguage(
        item: LangItem,
        model: ModelInfo,
        switchNow: Boolean
    ) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val sourceLang = resolveSourceLangForModel(model)
        Thread {
            try {
                runOnUiThread { tvModelStatus.text = getString(R.string.download_progress, 0, model.id) }
                val modelRoot = ModelManager.downloadAndInstall(this, model.id, model.url) { p ->
                    runOnUiThread { tvModelStatus.text = getString(R.string.download_progress, p, model.id) }
                }
                prefs.edit {
                    putString("asr_model_path_${model.id}", modelRoot.absolutePath)
                    putString(PREF_MODEL_FOR_LANG_PREFIX + item.langKey, model.id)
                    if (!sourceLang.isNullOrBlank()) {
                        putString(PREF_SOURCE_LANG_FOR_MODEL_PREFIX + model.id, sourceLang)
                    }
                    if (switchNow) {
                        putString(PREF_MODEL_ID, model.id)
                            .putString("asr_lang", model.id)
                    }
                }
                runOnUiThread { tvModelStatus.text = getString(R.string.download_complete, model.id) }
            } catch (e: Exception) {
                runOnUiThread {
                    tvModelStatus.text = getString(
                        R.string.download_failed,
                        e.message ?: getString(R.string.error_unknown)
                    )
                }
            }
        }.start()
    }

    @SuppressLint("SetTextI18n")
    private fun startCustomModelDownload(modelId: String, url: String) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val sourceLang = AsrLanguageResolver.resolveTranslateSourceLanguage(modelId)
        Thread {
            try {
                runOnUiThread { tvModelStatus.text = getString(R.string.download_progress, 0, modelId) }
                val modelRoot = ModelManager.downloadAndInstall(this, modelId, url) { p ->
                    runOnUiThread { tvModelStatus.text = getString(R.string.download_progress, p, modelId) }
                }
                prefs.edit {
                    putString(PREF_MODEL_ID, modelId)
                        .putString("asr_lang", modelId) // 兼容旧逻辑
                        .putString("asr_model_path_$modelId", modelRoot.absolutePath)
                    if (!sourceLang.isNullOrBlank()) {
                        putString(PREF_SOURCE_LANG_FOR_MODEL_PREFIX + modelId, sourceLang)
                    }
                }
                runOnUiThread { tvModelStatus.text = getString(R.string.download_complete, modelId) }
            } catch (e: Exception) {
                runOnUiThread {
                    tvModelStatus.text = getString(
                        R.string.download_failed,
                        e.message ?: getString(R.string.error_unknown)
                    )
                }
            }
        }.start()
    }

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

    private fun stripTags(html: String): String {
        return html
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace("&amp;", "&")
            .replace(Regex("<[^>]+>"), "")
            .trim()
    }

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

    private fun normalizeLangKey(name: String): String {
        return name.lowercase(Locale.ROOT)
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun normalizeLangDisplay(name: String): String {
        return name.lowercase(Locale.ENGLISH)
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

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

    private fun resolveSourceLangForModel(model: ModelInfo): String? {
        return model.lang?.let(::findLangCodeForName)
            ?: AsrLanguageResolver.resolveTranslateSourceLanguage(model.id)
    }

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

    private fun deriveModelIdFromUrl(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val name = clean.substringAfterLast('/')
        val base = if (name.endsWith(".zip")) name.dropLast(4) else name
        return base.ifBlank { "custom-model-${System.currentTimeMillis()}" }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun displayLangName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        val uiLocale = resources.configuration.locales[0] ?: Locale.getDefault()
        val name = locale.getDisplayName(uiLocale)
        return if (name.isNullOrBlank()) tag else name
    }
}
