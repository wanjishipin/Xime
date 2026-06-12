package com.kingzcheung.xime.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

data class UploadResult(
    val fileName: String,
    val success: Boolean,
    val error: String? = null
)

class WirelessImportHelper(private val context: Context) {
    private var server: EmbeddedServer<*, *>? = null

    private val _uploadResults = Channel<UploadResult>(Channel.BUFFERED)
    val uploadResults: Flow<UploadResult> = _uploadResults.receiveAsFlow()

    fun getLocalIpAddress(): String? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wifi?.connectionInfo?.ipAddress ?: return null
            String.format("%d.%d.%d.%d",
                ip and 0xff,
                (ip shr 8) and 0xff,
                (ip shr 16) and 0xff,
                (ip shr 24) and 0xff
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing ACCESS_WIFI_STATE permission", e)
            null
        }
    }

    fun findAvailablePort(): Int {
        for (port in 5000..5099) {
            try {
                val sock = java.net.ServerSocket()
                sock.bind(java.net.InetSocketAddress(port))
                sock.close()
                return port
            } catch (_: Exception) { }
        }
        return (5000..5099).firstOrNull { port ->
            try {
                java.net.ServerSocket(port).use { true }
            } catch (_: Exception) { false }
        } ?: 0
    }

    fun start(port: Int): String? {
        if (server != null) return null
        val ip = getLocalIpAddress() ?: return null
        val url = "http://$ip:$port"

        server = embeddedServer(CIO, port = port) {
            routing {
                get("/") {
                    call.respondText(HTML_PAGE, ContentType.Text.Html)
                }
                post("/upload") {
                    val rimeDir = File(context.filesDir, "rime")
                    rimeDir.mkdirs()
                    val tmpFile = File.createTempFile("upload_", ".tmp", rimeDir)
                    var lastName = ""

                    try {
                        // 1. 流式写入临时文件（不占堆内存）
                        val ch = call.receiveChannel()
                        java.io.FileOutputStream(tmpFile).use { fos ->
                            val buf = ByteArray(8192)
                            while (!ch.isClosedForRead) {
                                val n = ch.readAvailable(buf, 0, buf.size)
                                if (n <= 0) break
                                fos.write(buf, 0, n)
                            }
                        }

                        // 2. 从临时文件解析 multipart（RandomAccessFile 流式读取）
                        val ctHeader = call.request.headers["Content-Type"] ?: ""
                        val boundary = ctHeader.split("boundary=").getOrNull(1)?.trim()
                        if (boundary.isNullOrEmpty()) {
                            call.respondText("""{"success":false,"error":"No boundary"}""",
                                ContentType.Application.Json, HttpStatusCode.BadRequest)
                            return@post
                        }
                        val boundaryBytes = ("\r\n--$boundary").toByteArray()
                        val firstBoundary = ("--$boundary").toByteArray()
                        val headerEndMarker = "\r\n\r\n".toByteArray()

                        var saved = false
                        java.io.RandomAccessFile(tmpFile, "r").use { raf ->
                            var pos = findBytes(raf, firstBoundary, 0)
                            if (pos < 0) return@use

                            while (true) {
                                val partStart = pos + firstBoundary.size
                                val nextB = findBytes(raf, boundaryBytes, partStart)
                                if (nextB < 0) break
                                val partEnd = nextB

                                // 解析 part header
                                raf.seek(partStart.toLong())
                                val hdr = ByteArray(4096)
                                val hdrLen = readUntil(raf, hdr, headerEndMarker)
                                if (hdrLen < 0) { pos = nextB + 2; continue }

                                val headerStr = hdr.decodeToString(0, hdrLen)
                                val fn = Regex("""filename="([^"]*)"""").find(headerStr)
                                val name = fn?.groupValues?.getOrNull(1)
                                if (name.isNullOrEmpty() || name == "blob") { pos = nextB + 2; continue }

                                lastName = name
                                val contentStart = raf.filePointer
                                val contentLen = partEnd - contentStart

                                when {
                                    name.endsWith(".zip", ignoreCase = true) ||
                                    name.endsWith(".tar.gz", ignoreCase = true) ||
                                    name.endsWith(".tgz", ignoreCase = true) -> {
                                        val isTarGz = name.endsWith(".tar.gz", ignoreCase = true) || name.endsWith(".tgz", ignoreCase = true)
                                        val istream = object : java.io.InputStream() {
                                            var remaining = contentLen
                                            var buf8 = ByteArray(8192)
                                            override fun read(): Int {
                                                if (remaining <= 0) return -1
                                                val n = raf.read(buf8, 0, 1)
                                                if (n <= 0) return -1
                                                remaining--
                                                return buf8[0].toInt() and 0xff
                                            }
                                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                                if (remaining <= 0) return -1
                                                val cnt = if (remaining < len) remaining.toInt() else len
                                                val n = raf.read(b, off, cnt)
                                                if (n <= 0) return -1
                                                remaining -= n
                                                return n
                                            }
                                            override fun available() = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
                                        }
                                        val ok = if (isTarGz)
                                            SchemaManager.importTarGzFromStream(istream, rimeDir)
                                        else
                                            SchemaManager.importZipFromStream(istream, rimeDir)
                                        if (ok) {
                                            saved = true
                                            _uploadResults.trySend(UploadResult(fileName = name, success = true))
                                        } else {
                                            _uploadResults.trySend(UploadResult(fileName = name, success = false, error = "解压失败"))
                                        }
                                    }
                                    name.endsWith(".yaml") || name.endsWith(".schema.yaml") || name.endsWith(".dict.yaml") -> {
                                        raf.seek(contentStart)
                                        val data = ByteArray(contentLen.toInt())
                                        raf.readFully(data)
                                        File(rimeDir, name).writeBytes(data)
                                        saved = true
                                        _uploadResults.trySend(UploadResult(fileName = name, success = true))
                                    }
                                    else -> {
                                        _uploadResults.trySend(UploadResult(fileName = name, success = false, error = "不支持的文件类型"))
                                    }
                                }
                                pos = nextB + 2
                            }
                        }

                        if (saved) {
                            call.respondText("""{"success":true,"file":"$lastName"}""", ContentType.Application.Json)
                        } else {
                            call.respondText("""{"success":false,"error":"No valid file (supported: .yaml, .zip, .tar.gz)"}""",
                                ContentType.Application.Json, HttpStatusCode.BadRequest)
                        }
                    } finally {
                        tmpFile.delete()
                    }
                }
            }
        }.start(wait = false)

        Log.i(TAG, "Server started at $url")
        return url
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        Log.i(TAG, "Server stopped")
    }

    val isRunning: Boolean get() = server != null

    /** 在 RandomAccessFile 中查找字节模式，返回起始位置，未找到返回 -1 */
    private fun findBytes(raf: java.io.RandomAccessFile, pattern: ByteArray, startPos: Long): Long {
        val buf = ByteArray(8192)
        var pos = startPos
        while (pos < raf.length()) {
            raf.seek(pos)
            val n = raf.read(buf)
            if (n <= 0) break
            val end = n - pattern.size
            for (i in 0..end) {
                var match = true
                for (j in pattern.indices) {
                    if (buf[i + j] != pattern[j]) { match = false; break }
                }
                if (match) return pos + i
            }
            pos += maxOf(1, n - pattern.size + 1)
        }
        return -1
    }

    /** 从 RandomAccessFile 当前位置读取到 endMarker 为止，返回读取的字节数 */
    private fun readUntil(raf: java.io.RandomAccessFile, buf: ByteArray, endMarker: ByteArray): Int {
        var bufPos = 0
        while (bufPos < buf.size) {
            val b = raf.read()
            if (b < 0) break
            buf[bufPos++] = b.toByte()
            // 检查是否匹配 endMarker
            if (bufPos >= endMarker.size) {
                var match = true
                for (i in endMarker.indices) {
                    if (buf[bufPos - endMarker.size + i] != endMarker[i]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    return bufPos - endMarker.size
                }
            }
        }
        return -1
    }

    fun generateQrCode(url: String, size: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y,
                        if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "QR generation failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "WirelessImport"

        private val HTML_PAGE = """<!DOCTYPE html>
<html lang=zh>
<head>
<meta charset=utf-8>
<meta name=viewport content='width=device-width,initial-scale=1'>
<title>Xime 输入法 - 导入方案</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,sans-serif;background:#f5f5f7;display:flex;justify-content:center;padding:40px 16px}
.card{background:#fff;border-radius:16px;padding:32px;width:100%;max-width:500px;box-shadow:0 2px 12px rgba(0,0,0,.08)}
h1{font-size:22px;margin-bottom:8px;color:#1d1d1f}
p{color:#666;margin-bottom:24px;font-size:14px}
.drop-zone{border:2px dashed #c7c7cc;border-radius:12px;padding:40px 20px;text-align:center;cursor:pointer;transition:all .2s}
.drop-zone.dragover{border-color:#007aff;background:#e8f0fe}
.drop-zone.has-file{border-color:#34c759;background:#e8f8ed}
.drop-zone-icon{font-size:40px;margin-bottom:12px;color:#8e8e93}
.drop-zone-text{color:#8e8e93;font-size:14px}
.drop-zone-hint{color:#c7c7cc;font-size:12px;margin-top:8px}
button{background:#007aff;color:#fff;border:none;border-radius:10px;padding:12px 24px;font-size:16px;cursor:pointer;width:100%;margin-top:16px}
button:disabled{background:#c7c7cc;cursor:default}
.file-list{margin-top:16px}
.file-item{display:flex;justify-content:space-between;align-items:center;padding:8px 12px;background:#f5f5f7;border-radius:8px;margin-top:8px;font-size:13px;position:relative;overflow:hidden}
.file-item .bar{position:absolute;left:0;top:0;height:100%;background:#b3d9ff;transition:width .2s;z-index:0}
.file-item .z{position:relative;z-index:1;display:flex;justify-content:space-between;align-items:center;width:100%}
.file-item .name{color:#1d1d1f}
.file-item .s{font-size:12px;white-space:nowrap;margin-left:8px}
.file-item .s.ok{color:#34c759}
.file-item .s.fail{color:#ff3b30}
.status-msg{margin-top:16px;padding:12px;border-radius:8px;text-align:center;font-size:14px;display:none}
.status-msg.success{display:block;background:#e8f8ed;color:#34c759}
.status-msg.error{display:block;background:#ffe8e8;color:#ff3b30}
</style>
</head>
<body>
<div class=card>
<h1>导入输入方案</h1>
<p>将 .schema.yaml / .dict.yaml 拖拽到下方，或点击选择</p>
<div class=drop-zone id=dz onclick="document.getElementById('fi').click()">
<div class=drop-zone-icon>&#128196;</div>
<div class=drop-zone-text>拖拽文件到此处</div>
<div class=drop-zone-hint>支持 .schema.yaml / .dict.yaml / .zip / .tar.gz</div>
<div style='color:#8e8e93;font-size:12px;margin-top:12px;padding:10px;background:#f5f5f7;border-radius:8px;line-height:1.5'>💡 多文件或复杂目录结构，请打包为 .zip 或 .tar.gz 上传</div>
</div>
<input type=file id=fi accept='.yaml,.zip,.tar.gz,.tgz' multiple style='display:none'>
<div id=fl></div>
<button id=ub disabled onclick=up()>上传</button>
<div id=sm></div>
</div>
<script>
var dz=document.getElementById('dz'),fi=document.getElementById('fi'),fl=document.getElementById('fl'),ub=document.getElementById('ub'),sm=document.getElementById('sm'),fs=[]
function ok(n){return n.endsWith('.schema.yaml')||n.endsWith('.dict.yaml')||n.endsWith('.yaml')||n.endsWith('.zip')||n.endsWith('.tar.gz')||n.endsWith('.tgz')}
function r(){var h='';for(var i=0;i<fs.length;i++){h+='<div class=file-item><div class=bar id=b'+i+' style=width:0%></div><div class=z><span class=name>'+fs[i].name+'</span><span class=s id=s'+i+'>待上传</span></div></div>'}fl.innerHTML=h;ub.disabled=fs.length===0;dz.className='drop-zone'+(fs.length?' has-file':'')}
dz.ondragover=function(e){e.preventDefault();dz.classList.add('dragover')}
dz.ondragleave=function(){dz.classList.remove('dragover')}
dz.ondrop=function(e){e.preventDefault();dz.classList.remove('dragover');add(Array.from(e.dataTransfer.files))}
fi.onchange=function(){add(Array.from(fi.files))}
function add(list){for(var i=0;i<list.length;i++){var f=list[i];if(ok(f.name)){var d=false;for(var j=0;j<fs.length;j++){if(fs[j].name===f.name){d=true;break}}if(!d)fs.push(f)}else{alert('不支持的类型: '+f.name)}}r()}
function p(i,pct){var e=document.getElementById('b'+i);if(e){e.style.width=Math.min(pct,100)+'%'}var t=document.getElementById('s'+i);if(t){if(pct<100&&pct>=0){t.innerHTML='上传中 '+pct+'%'}else if(pct>=100){t.className='s ok';t.innerHTML='已完成'}}}
function up(){if(fs.length===0)return;ub.disabled=true;ub.innerHTML='上传中...';sm.innerHTML='';sm.className='';var ok=0,fail=0,n=fs.length;function d(){if(ok+fail===n){sm.innerHTML=ok+'/'+n+' 个成功'+(fail?'，'+fail+' 个失败':'')+'，请在手机上部署';sm.className='status-msg'+(fail?' error':' success');if(fail===0)fs=[];r();ub.disabled=false;ub.innerHTML='上传'}}
for(var i=0;i<fs.length;i++){(function(idx){var fd=new FormData();fd.append('file',fs[idx]);var x=new XMLHttpRequest();x.open('POST','/upload',true);x.upload.onprogress=function(e){if(e.lengthComputable)p(idx,Math.round(e.loaded/e.total*100))};x.onload=function(){try{var j=JSON.parse(x.responseText);if(j.success){ok++;p(idx,100)}else{fail++;p(idx,100)}}catch(e){fail++;p(idx,100)};d()};x.onerror=function(){fail++;p(idx,100);d()};p(idx,0);x.send(fd)})(i)}}
</script>
</body>
</html>"""
    }
}
