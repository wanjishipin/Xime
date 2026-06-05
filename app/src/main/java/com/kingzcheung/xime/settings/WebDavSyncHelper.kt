package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.concurrent.TimeUnit

data class WebDavFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0
)

object WebDavSyncHelper {
    private const val TAG = "WebDavSyncHelper"

    private fun buildClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private fun authHeaders(username: String, password: String): Map<String, String> {
        return if (username.isNotEmpty()) {
            mapOf("Authorization" to Credentials.basic(username, password))
        } else emptyMap()
    }

    suspend fun testConnection(
        baseUrl: String,
        username: String,
        password: String,
        remotePath: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val client = buildClient()
            val headers = authHeaders(username, password)
            val url = normalizeUrl(baseUrl)
            val testUrl = if (remotePath.isNotBlank()) "$url/$remotePath" else url
            val request = Request.Builder()
                .url(testUrl)
                .method("PROPFIND", null)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("Depth", "0")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful || response.code == 404 || response.code == 405) {
                // 404 = path doesn't exist yet (can be created), 405 = already exists
                Result.success("连接成功")
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            Result.failure(e)
        }
    }

    private val userConfigFiles = setOf("default.custom.yaml", "user.yaml", "installation.yaml")

    private fun isSyncableFile(name: String): Boolean {
        return name.endsWith(".yaml") || name.endsWith(".txt")
    }

    suspend fun uploadSchemas(
        context: Context,
        baseUrl: String,
        username: String,
        password: String,
        remotePath: String,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = buildClient()
            val headers = authHeaders(username, password)
            val base = normalizeUrl(baseUrl)
            val remoteBase = "$base/$remotePath/rime"

            val rimeDir = File(context.filesDir, "rime")

            ensureRemoteDir(client, remoteBase, headers)

            if (rimeDir.exists()) {
                rimeDir.walkTopDown().forEach { file ->
                    if (file.isFile && isSyncableFile(file.name)) {
                        val relativePath = file.relativeTo(rimeDir).path
                        val remoteUrl = "$remoteBase/$relativePath"

                        // 确保远程父目录存在
                        val parentDir = relativePath.substringBeforeLast('/', "")
                        if (parentDir.isNotEmpty()) {
                            ensureRemoteDir(client, "$remoteBase/$parentDir", headers)
                        }

                        onProgress("上传 $relativePath")
                        val err = uploadFile(client, remoteUrl, file, headers)
                        if (err != null) {
                            onProgress("上传 $relativePath 失败: $err")
                            return@withContext false
                        }
                    }
                }
            }

            onProgress("上传完成")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            onProgress("上传失败: ${e.message}")
            false
        }
    }

    suspend fun downloadSchemas(
        context: Context,
        baseUrl: String,
        username: String,
        password: String,
        remotePath: String,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = buildClient()
            val headers = authHeaders(username, password)
            val base = normalizeUrl(baseUrl)
            val remoteBase = "$base/$remotePath/rime"

            val rimeDir = File(context.filesDir, "rime")
            if (!rimeDir.exists()) rimeDir.mkdirs()

            onProgress("读取远程文件列表...")
            val remoteFiles = listRemoteDirRecursive(client, remoteBase, headers)

            for (remoteFile in remoteFiles.filter { !it.isDirectory && isSyncableFile(it.name) }) {
                // 计算相对于 remoteBase 的相对路径
                val remoteFileUrl = remoteFile.path
                val relativePath = if (remoteFileUrl.startsWith(remoteBase)) {
                    remoteFileUrl.removePrefix(remoteBase).trimStart('/')
                } else {
                    remoteFile.name
                }

                val localFile = File(rimeDir, relativePath)
                localFile.parentFile?.mkdirs()

                val fullUrl = if (remoteFileUrl.startsWith("http")) remoteFileUrl
                    else "$remoteBase/$relativePath"

                onProgress("下载 $relativePath")
                val err = downloadFile(client, fullUrl, localFile, headers)
                if (err != null) {
                    onProgress("下载 $relativePath 失败: $err")
                    return@withContext false
                }
            }

            onProgress("下载完成")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            onProgress("下载失败: ${e.message}")
            false
        }
    }

    private fun normalizeUrl(url: String): String {
        var normalized = url.trimEnd('/')
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        return normalized
    }

    private fun ensureRemoteDir(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>
    ) {
        val path = url.substringAfter("://").substringAfter('/')
        if (path.isEmpty()) return
        val segments = path.split('/').filter { it.isNotEmpty() }
        val base = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore('/')
        var current = base
        for (segment in segments) {
            current += "/$segment"
            try {
                val request = Request.Builder()
                    .url(current)
                    .method("MKCOL", null)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful && response.code != 405) {
                    Log.w(TAG, "MKCOL $current returned ${response.code}")
                }
                response.close()
            } catch (e: Exception) {
                Log.w(TAG, "MKCOL $current exception: ${e.message}")
            }
        }
    }

    private fun uploadFile(
        client: OkHttpClient,
        url: String,
        file: File,
        headers: Map<String, String>
    ): String? {
        return try {
            val body = file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(url)
                .put(body)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            val response = client.newCall(request).execute()
            val msg = if (response.isSuccessful) null
                else "HTTP ${response.code} ${response.message}"
            response.close()
            msg
        } catch (e: Exception) {
            Log.e(TAG, "PUT $url exception", e)
            e.message ?: "未知错误"
        }
    }

    private fun downloadFile(
        client: OkHttpClient,
        url: String,
        localFile: File,
        headers: Map<String, String>
    ): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.byteStream()?.use { input ->
                    localFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                null
            } else {
                "HTTP ${response.code} ${response.message}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET $url exception", e)
            e.message ?: "未知错误"
        }
    }

    private fun listRemoteDir(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>
    ): List<WebDavFile> {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", null)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Depth", "1")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()

        val body = response.body?.string() ?: return emptyList()
        return parsePropfindResponse(body, url)
    }

    private fun listRemoteDirRecursive(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>
    ): List<WebDavFile> {
        val result = mutableListOf<WebDavFile>()
        val entries = listRemoteDir(client, url, headers)
        for (entry in entries) {
            if (entry.isDirectory) {
                // 递归列出子目录
                val subUrl = if (entry.path.startsWith("http")) entry.path
                    else "$url/${entry.name}"
                result.addAll(listRemoteDirRecursive(client, subUrl, headers))
            } else {
                result.add(entry)
            }
        }
        return result
    }

    private fun parsePropfindResponse(xml: String, baseUrl: String): List<WebDavFile> {
        val files = mutableListOf<WebDavFile>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())
            var eventType = parser.eventType
            var currentHref = ""
            var currentIsDir = false
            var currentSize = 0L
            var inResponse = false
            var inHref = false
            var inCollection = false
            var inContentLength = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name?.substringAfter(':') ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (tag) {
                            "response" -> inResponse = true
                            "href" -> inHref = true
                            "collection" -> if (inResponse) inCollection = true
                            "getcontentlength" -> inContentLength = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inHref) currentHref = parser.text.trim()
                        if (inContentLength) {
                            currentSize = parser.text.trim().toLongOrNull() ?: 0L
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (tag) {
                            "response" -> {
                                if (inResponse && currentHref.isNotEmpty()) {
                                    val name = currentHref.substringAfterLast('/').trimEnd('/')
                                    if (name.isNotEmpty()) {
                                        files.add(WebDavFile(
                                            name = name,
                                            path = currentHref,
                                            isDirectory = inCollection,
                                            size = currentSize
                                        ))
                                    }
                                }
                                inResponse = false
                                inCollection = false
                                currentHref = ""
                                currentSize = 0L
                            }
                            "href" -> inHref = false
                            "getcontentlength" -> inContentLength = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PROPFIND response", e)
        }
        return files
    }
}
