package com.example.subtitletranslation.model

import android.content.Context
import com.example.subtitletranslation.R
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 管理 Vosk 语音识别模型的下载、解压、校验和本地路径解析。
 * MainActivity 和 OverlayService 都通过这里与模型文件系统交互。
 */
object ModelManager {

    // 超大模型在手机上可能更慢、更吃内存，但不再直接阻止安装。
    private const val LARGE_MODEL_WARNING_BYTES = 600L * 1024L * 1024L

    fun modelRoot(ctx: Context): File = File(ctx.filesDir, "vosk_models")

    fun modelDir(ctx: Context, lang: String): File = modelDir(modelRoot(ctx), lang)

    fun modelDir(root: File, lang: String): File = File(root, lang)

    fun selectWritableModelRoot(ctx: Context, requiredBytesHint: Long? = null): File {
        val minFreeBytes = requiredBytesHint?.coerceAtLeast(0L) ?: 0L
        return candidateRoots(ctx)
            .sortedWith(
                compareByDescending<File> { it.usableSpace >= minFreeBytes }
                    .thenByDescending { it.usableSpace }
            )
            .first()
    }

    fun isInstalled(ctx: Context, lang: String): Boolean {
        val resolved = resolveInstalledPath(ctx, lang) ?: return false
        return validateInstalledModel(ctx, resolved, checkRuntimeSize = false) == null
    }

    fun resolveInstalledPath(ctx: Context, lang: String): File? {
        for (root in candidateRoots(ctx)) {
            val dest = modelDir(root, lang)
            if (!dest.exists() || !dest.isDirectory) continue
            // 解压包有时会多包一层目录，这里统一收敛到真正的模型根目录。
            val resolved = resolveModelRoot(dest)
            if (resolved.exists()) return resolved
        }
        return null
    }

    // 启动识别前做结构校验，避免把损坏或桌面端模型直接交给 Vosk。
    fun validateInstalledModel(
        ctx: Context,
        modelDir: File,
        checkRuntimeSize: Boolean = true
    ): String? {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return ctx.getString(R.string.error_model_invalid_layout, modelDir.absolutePath)
        }
        if (!File(modelDir, "conf/model.conf").isFile) {
            return ctx.getString(R.string.error_model_invalid_layout, "conf/model.conf")
        }
        if (!File(modelDir, "am/final.mdl").isFile) {
            return ctx.getString(R.string.error_model_invalid_layout, "am/final.mdl")
        }

        val graphDir = File(modelDir, "graph")
        val hasDecodingGraph =
            File(graphDir, "HCLG.fst").isFile ||
                    (File(graphDir, "HCLr.fst").isFile && File(graphDir, "Gr.fst").isFile)
        if (!hasDecodingGraph) {
            return ctx.getString(R.string.error_model_invalid_layout, "graph/HCLG.fst")
        }

        if (checkRuntimeSize) {
            // 兼容旧调用方：大模型只做提醒，不再因为体积过大直接判定为无效。
        }

        return null
    }


    fun downloadAndInstall(
        ctx: Context,
        lang: String,
        zipUrl: String,
        onProgress: (percent: Int) -> Unit
    ): File {
        val root = selectWritableModelRoot(ctx)
        if (!root.exists() && !root.mkdirs()) {
            throw java.io.IOException(
                ctx.getString(R.string.error_create_model_root, root.absolutePath)
            )
        }

        val dest = modelDir(root, lang)
        // 先下载到临时目录，全部成功后再替换正式目录，避免半下载状态污染现有模型。
        val staging = File(root, ".$lang.tmp-${System.currentTimeMillis()}")
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs()) {
            throw java.io.IOException(
                ctx.getString(R.string.error_create_language_dir, staging.absolutePath)
            )
        }

        try {
            val zipFile = File(staging, "model.zip")
            downloadFile(ctx, zipUrl, zipFile, onProgress)

            val extractedRoot = unzip(zipFile, staging)
            zipFile.delete()

            val resolved = resolveModelRoot(extractedRoot)
            val validationError = validateInstalledModel(ctx, resolved, checkRuntimeSize = true)
            if (validationError != null) {
                throw java.io.IOException(validationError)
            }

            // 安装新模型前先删除旧目录，避免新旧文件混在一起。
            removeOtherInstalledCopies(ctx, lang, keepRoot = root)
            if (dest.exists() && !dest.deleteRecursively()) {
                throw java.io.IOException(
                    ctx.getString(R.string.error_replace_model_dir, dest.absolutePath)
                )
            }
            if (!staging.renameTo(dest)) {
                staging.copyRecursively(dest, overwrite = true)
                staging.deleteRecursively()
            }

            return resolveInstalledPath(ctx, lang)
                ?: throw java.io.IOException(
                    ctx.getString(R.string.error_model_invalid_layout, dest.absolutePath)
                )
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    private fun downloadFile(ctx: Context, url: String, outFile: File, onProgress: (Int) -> Unit) {
        outFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw java.io.IOException(
                    ctx.getString(R.string.error_create_parent_dir, parent.absolutePath)
                )
            }
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
        }

        // 先检查 HTTP 状态，再开始写文件，避免把错误页保存成 zip。
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) {
            throw java.io.IOException(ctx.getString(R.string.error_download_http, code, url))
        }

        conn.inputStream.use { input ->
            FileOutputStream(outFile).use { output ->
                val total = conn.contentLengthLong.coerceAtLeast(0)
                val buf = ByteArray(64 * 1024)
                var read: Int
                var done = 0L
                while (input.read(buf).also { read = it } >= 0) {
                    output.write(buf, 0, read)
                    done += read
                    if (total > 0) {
                        onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
    }

    private fun unzip(zipFile: File, destDir: File): File {
        val canonicalDest = destDir.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outPath = File(destDir, entry.name)
                val canonicalOut = outPath.canonicalFile
                // 防止 zip-slip，把解压目标限制在 staging 目录内部。
                val isInsideDest = canonicalOut.path == canonicalDest.path ||
                        canonicalOut.path.startsWith(canonicalDest.path + File.separator)
                if (!isInsideDest) {
                    throw java.io.IOException("Bad zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    canonicalOut.mkdirs()
                } else {
                    canonicalOut.parentFile?.mkdirs()
                    FileOutputStream(canonicalOut).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return destDir
    }

    // Vosk 包可能直接是模型目录，也可能外面再套一层版本目录，这里做兼容。
    private fun resolveModelRoot(root: File): File {
        if (looksLikeModelRoot(root)) return root
        val children = root.listFiles()?.filter {
            it.isDirectory && !it.name.startsWith(".") && it.name != "__MACOSX"
        } ?: emptyList()
        if (children.size == 1 && looksLikeModelRoot(children[0])) {
            return children[0]
        }
        return if (children.size == 1) children[0] else root
    }

    // Public version for use by ModelDownloadService
    fun resolveModelRootPublic(root: File): File = resolveModelRoot(root)

    // Public version for use by ModelDownloadService
    fun unzipModel(zipFile: File, destDir: File): File = unzip(zipFile, destDir)

    fun isLargeModel(modelDir: File): Boolean = directorySize(modelDir) > LARGE_MODEL_WARNING_BYTES

    fun getLargeModelWarning(ctx: Context, modelDir: File): String? {
        return if (isLargeModel(modelDir)) {
            ctx.getString(R.string.error_model_too_large_mobile)
        } else {
            null
        }
    }

    fun removeOtherInstalledCopies(ctx: Context, lang: String, keepRoot: File) {
        deleteOtherInstalledCopies(ctx, lang, keepRoot)
    }

    private fun looksLikeModelRoot(dir: File): Boolean {
        return File(dir, "conf").isDirectory || File(dir, "am").isDirectory || File(dir, "graph").isDirectory
    }

    // 用于粗略判断模型是否大到不适合移动端实时使用。
    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .fold(0L) { total, file -> total + file.length() }
    }

    private fun candidateRoots(ctx: Context): List<File> {
        val roots = linkedSetOf<File>()
        roots.add(File(ctx.filesDir, "vosk_models"))
        ctx.getExternalFilesDir(null)?.let { roots.add(File(it, "vosk_models")) }
        return roots.toList()
    }

    private fun deleteOtherInstalledCopies(ctx: Context, lang: String, keepRoot: File) {
        for (root in candidateRoots(ctx)) {
            if (root.absolutePath == keepRoot.absolutePath) continue
            val dir = modelDir(root, lang)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }
}
