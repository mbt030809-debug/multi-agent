package com.mbta.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val notes: String,
    val apkUrl: String
)

object AppUpdater {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/mbt030809-debug/multi-agent/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun getCurrentVersionName(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        }.getOrElse { "0.0.0" }
    }

    fun isNewerVersion(remote: String, current: String): Boolean = VersionCompare.isNewer(remote, current)

    suspend fun checkLatestRelease(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(LATEST_RELEASE_API)
                .addHeader("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val assets = root["assets"]?.jsonArray ?: return@withContext null
                val apkUrl = assets.firstNotNullOfOrNull { el ->
                    val obj = el.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        obj["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    } else null
                } ?: return@withContext null

                val current = getCurrentVersionName(context)
                if (isNewerVersion(tag, current)) {
                    UpdateInfo(
                        tagName = tag,
                        versionName = tag.removePrefix("v"),
                        notes = notes,
                        apkUrl = apkUrl
                    )
                } else null
            }
        }.getOrNull()
    }

    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(dir, "MbtA-update.apk")
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                body.byteStream().use { input ->
                    java.io.BufferedOutputStream(FileOutputStream(outFile), 65536).use { output ->
                        val buf = ByteArray(65536)
                        var copied = 0L
                        var lastEmit = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
                            copied += read
                            val now = System.currentTimeMillis()
                            if (total > 0 && now - lastEmit >= 120L) {
                                lastEmit = now
                                onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                        if (total > 0) onProgress(1f)
                    }
                }
            }
            outFile
        }.getOrNull()
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permIntent)
            return false
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }
}
