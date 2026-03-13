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

object ModelManager {

    private const val MAX_RECOMMENDED_MODEL_BYTES = 600L * 1024L * 1024L

    fun modelRoot(ctx: Context): File = File(ctx.filesDir, "vosk_models")

    fun modelDir(ctx: Context, lang: String): File = File(modelRoot(ctx), lang)

    fun isInstalled(ctx: Context, lang: String): Boolean {
        val resolved = resolveInstalledPath(ctx, lang) ?: return false
        return validateInstalledModel(ctx, resolved, checkRuntimeSize = false) == null
    }

    fun resolveInstalledPath(ctx: Context, lang: String): File? {
        val dest = modelDir(ctx, lang)
        if (!dest.exists() || !dest.isDirectory) return null
        val resolved = resolveModelRoot(dest)
        return if (resolved.exists()) resolved else null
    }

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

        if (checkRuntimeSize && directorySize(modelDir) > MAX_RECOMMENDED_MODEL_BYTES) {
            return ctx.getString(R.string.error_model_too_large_mobile)
        }

        return null
    }


    fun downloadAndInstall(
        ctx: Context,
        lang: String,
        zipUrl: String,
        onProgress: (percent: Int) -> Unit
    ): File {
        val root = modelRoot(ctx)
        if (!root.exists() && !root.mkdirs()) {
            throw java.io.IOException(
                ctx.getString(R.string.error_create_model_root, root.absolutePath)
            )
        }

        val dest = modelDir(ctx, lang)
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

        // ✅ 先 connect & 检查 HTTP 状态码
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

    private fun looksLikeModelRoot(dir: File): Boolean {
        return File(dir, "conf").isDirectory || File(dir, "am").isDirectory || File(dir, "graph").isDirectory
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .fold(0L) { total, file -> total + file.length() }
    }
}
