package com.mbta.app.network

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** AI 응답의 파일 카드(링크/로컬 경로)를 기기 Download 폴더로 저장 */
object FileDownloader {
    data class Saved(val uri: Uri?, val name: String, val mime: String, val location: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    fun bridgeUrl(path: String): String =
        "http://127.0.0.1:8765/download?path=" + URLEncoder.encode(path, "UTF-8")

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "md", "log" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    /** 웹 링크(로그인 쿠키 포함) 또는 브리지 다운로드 URL을 내려받아 저장. 실패하면 null */
    suspend fun download(context: Context, url: String, name: String, useWebCookies: Boolean): Saved? =
        withContext(Dispatchers.IO) {
            runCatching {
                val rb = Request.Builder().url(url)
                if (useWebCookies) {
                    CookieManager.getInstance().getCookie(url)?.let { rb.addHeader("Cookie", it) }
                    rb.addHeader("User-Agent", UA)
                }
                client.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body ?: return@use null
                    val safe = name.ifBlank { "download" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val headerMime = resp.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
                    val mime = if (headerMime.isBlank() || headerMime == "application/octet-stream" || headerMime.startsWith("text/html")) {
                        guessMime(safe)
                    } else headerMime
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, safe)
                            put(MediaStore.Downloads.MIME_TYPE, mime)
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: return@use null
                        context.contentResolver.openOutputStream(uri)?.use { out -> body.byteStream().copyTo(out) }
                            ?: return@use null
                        Saved(uri, safe, mime, "Download/$safe")
                    } else {
                        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }
                            ?: return@use null
                        val f = File(dir, safe)
                        f.outputStream().use { out -> body.byteStream().copyTo(out) }
                        Saved(null, safe, mime, f.absolutePath)
                    }
                }
            }.getOrNull()
        }

    /** 브리지에 파일이 실제로 있는지 확인 */
    suspend fun bridgeFileExists(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("http://127.0.0.1:8765/filestat?path=" + URLEncoder.encode(path, "UTF-8"))
                .get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
