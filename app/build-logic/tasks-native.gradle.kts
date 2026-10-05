import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import org.gradle.api.tasks.PathSensitivity

// 使用官方 Maven 预编译 onnxruntime-android AAR（内置 CPU + NNAPI EP），
// 不再从源码自行编译（自行编译需 30-60 分钟且依赖网络/NDK 稳定性）。
val onnxVersion = "1.28.0"
val onnxAarUrl = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${onnxVersion}/onnxruntime-android-${onnxVersion}.aar"
// 头文件不在 AAR 内，需从对应版本源码 raw 取（仅需 C API + provider factory 头）
val onnxHeadersBase = "https://raw.githubusercontent.com/microsoft/onnxruntime/v${onnxVersion}/include/onnxruntime"

// GitHub 加速镜像（按顺序尝试；不可用时请替换/删除）
val ghMirrors = listOf(
    "https://ghfast.top",
    "https://gh-proxy.com",
    "https://ghproxy.net",
)

// 纯 Java（HttpURLConnection）实现下载，跨平台且不依赖外部 curl/powershell：
//  - 不依赖构建环境 PATH 中是否有 curl（F-Droid 构建服务器曾因找不到 curl 而构建失败）
//  - 天然规避 Windows 下 curl/schannel TLS 握手失败（SSL error 35）问题
// 单个 URL 下载，支持断点续传与自动重试
fun downloadFile(url: String, target: File, workDir: File, desc: String): Boolean {
    println("Downloading $desc: $url")
    repeat(5) { attempt ->
        try {
            if (downloadOnce(url, target, desc)) {
                if (target.exists() && target.length() == 0L) {
                    target.delete()
                    return false
                }
                println("Downloaded ${target.name} (${target.length()} bytes)")
                return true
            }
        } catch (e: Exception) {
            System.err.println("Download error for $desc: ${e.message}")
        }
        // 失败后清空残留半成品，避免续传污染下一源
        target.delete()
        if (attempt < 4) Thread.sleep(3000)
    }
    return false
}

// 单次下载；返回 true 表示 HTTP 成功且内容已写入，false 表示 HTTP 层失败（如 404/403）
fun downloadOnce(url: String, target: File, desc: String): Boolean {
    val resumeFrom = if (target.exists()) target.length() else 0L
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    try {
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 600_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64)")
        if (resumeFrom > 0) {
            conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        val code = conn.responseCode
        if (code !in 200..299 && code != 206) {
            System.err.println("Download failed for $desc (HTTP $code)")
            return false
        }
        // 服务端支持 Range（206）才追加续传；否则（200）从头覆盖
        val append = code == 206 && resumeFrom > 0
        target.parentFile.mkdirs()
        conn.inputStream.use { input ->
            java.io.FileOutputStream(target, append).use { out ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                }
            }
        }
        return true
    } finally {
        conn.disconnect()
    }
}

// 按顺序尝试多个 URL（镜像优先，最后官方源），成功即停止，失败自动切换到下一个
fun downloadWithMirrors(urls: List<String>, target: File, workDir: File, desc: String): Boolean {
    for (url in urls) {
        // 已完整下载则跳过
        if (target.exists() && target.length() > 0) return true
        if (downloadFile(url, target, workDir, desc)) return true
        // 失败后清空残留半成品，避免续传污染下一源
        target.delete()
    }
    return false
}

fun githubUrls(url: String): List<String> = buildList {
    ghMirrors.forEach { add("${it.trimEnd('/')}/$url") }
    add(url) // 官方源兜底
}

val downloadOnnx by tasks.registering {
    val cppDir = file("src/main/jni/onnxruntime")
    val jniLibsDir = file("src/main/jniLibs")
    val nnapiMarker = file("$buildDir/onnxruntime-nnapi-marker")

    outputs.dir(cppDir)
    outputs.dir(jniLibsDir)
    outputs.file(nnapiMarker)

    doLast {
        val abis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

        // 已全部就绪则跳过
        val allSoPresent = abis.all {
            file("${jniLibsDir.absolutePath}/$it/libonnxruntime.so").exists() &&
            file("${cppDir.absolutePath}/lib/$it/libonnxruntime.so").exists()
        }
        val headersPresent = file("$cppDir/include/onnxruntime_c_api.h").exists()
        if (allSoPresent && headersPresent) {
            println("ONNX Runtime (official AAR) already deployed for all ABIs, skipping")
            return@doLast
        }

        // 1. 下载官方 AAR（含 4 ABI 的 libonnxruntime.so，内置 CPU + NNAPI EP）
        val aar = File(buildDir, "onnxruntime-android-${onnxVersion}.aar")
        if (!downloadWithMirrors(listOf(onnxAarUrl), aar, buildDir, "onnxruntime-android AAR")) {
            throw GradleException("Failed to download ONNX Runtime Android AAR: $onnxAarUrl")
        }
        println("AAR downloaded: ${aar.length()} bytes")

        // 2. 解压 AAR（zip），提取 jni/<abi>/libonnxruntime.so
        val aarDir = File(buildDir, "onnxruntime-aar")
        try {
            copy {
                from(zipTree(aar))
                into(aarDir)
            }
        } catch (e: Exception) {
            throw GradleException("Failed to extract AAR: ${e.message}")
        }

        for (abi in abis) {
            val src = File(aarDir, "jni/$abi/libonnxruntime.so")
            if (!src.exists()) throw GradleException("AAR missing jni/$abi/libonnxruntime.so")
            val abiLib = File(cppDir, "lib/$abi")
            val abiJni = File(jniLibsDir, abi)
            abiLib.mkdirs(); abiJni.mkdirs()
            src.copyTo(File(abiLib, "libonnxruntime.so"), overwrite = true)
            src.copyTo(File(abiJni, "libonnxruntime.so"), overwrite = true)
            println("Deployed libonnxruntime.so [$abi] (${src.length()} bytes)")
        }

        // 下载 v1.28 core/session/ 目录下全部头文件（c_api.h 依赖 ep_c_api.h、
        // error_code.h 等，需完整覆盖以通过编译），统一提升到 include 顶层供 #include 直接命中。
        val dstHeaders = File(cppDir, "include")
        dstHeaders.mkdirs()
        val headersToFetch = listOf(
            "core/session/environment.h",
            "core/session/experimental_onnxruntime_cxx_api.h",
            "core/session/experimental_onnxruntime_cxx_inline.h",
            "core/session/onnxruntime_c_api.h",
            "core/session/onnxruntime_cxx_api.h",
            "core/session/onnxruntime_cxx_inline.h",
            "core/session/onnxruntime_env_config_keys.h",
            "core/session/onnxruntime_ep_c_api.h",
            "core/session/onnxruntime_ep_device_ep_metadata_keys.h",
            "core/session/onnxruntime_error_code.h",
            "core/session/onnxruntime_experimental_c_api.h",
            "core/session/onnxruntime_experimental_c_api.inc",
            "core/session/onnxruntime_experimental_cxx_api.h",
            "core/session/onnxruntime_float16.h",
            "core/session/onnxruntime_lite_custom_op.h",
            "core/session/onnxruntime_run_options_config_keys.h",
            "core/session/onnxruntime_session_options_config_keys.h",
        )
        // 旧源码编译残留的 v1.28 头文件会与官方 v1.27 .so 不匹配（ORT_API_VERSION 不同），
        // 导致 GetApi(28) 失败。部署前清理 include 目录，确保头文件与 AAR 版本一致。
        dstHeaders.listFiles()?.forEach { old ->
            if (old.isFile) old.delete()
        }
        for (rel in headersToFetch) {
            val target = java.io.File(dstHeaders, rel.substringAfterLast("/"))
            val url = "$onnxHeadersBase/$rel"
            if (downloadWithMirrors(githubUrls(url), target, buildDir, "onnxruntime header $rel")) {
                println("Header ok: ${target.name}")
            } else {
                System.err.println("WARNING: failed to fetch header $rel")
            }
        }

        // 4. Marker
        nnapiMarker.parentFile.mkdirs()
        nnapiMarker.writeText("ONNX Runtime v${onnxVersion} (official AAR, CPU+NNAPI) deployed on ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")
        println("ONNX Runtime deployed to: ${abis.joinToString()}")
    }
}

val buildTrie by tasks.registering {
    val inputFile = file("src/main/assets/english.txt")
    val outputFile = file("src/main/assets/english_trie.bin")

    inputs.file(inputFile)
    outputs.file(outputFile)

    doLast {
        val words = inputFile.readLines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        println("Loaded ${words.size} words from ${inputFile.name}")

        val nodes = mutableListOf<MutableMap<Char, Int>>()
        val nodeWords = mutableListOf<String?>()
        val nodeFreqs = mutableListOf<Int>()
        nodes.add(mutableMapOf())
        nodeWords.add(null)
        nodeFreqs.add(0)

        fun getOrCreateChild(parentIndex: Int, char: Char): Int {
            val existing = nodes[parentIndex][char]
            if (existing != null) return existing

            val newIndex = nodes.size
            nodes.add(mutableMapOf())
            nodeWords.add(null)
            nodeFreqs.add(0)
            nodes[parentIndex][char] = newIndex
            return newIndex
        }

        words.forEachIndexed { lineNum, word ->
            var current = 0
            for (char in word) {
                current = getOrCreateChild(current, char)
            }
            if (nodeWords[current] == null) {
                nodeWords[current] = word
                nodeFreqs[current] = lineNum + 1
            }
        }

        println("Built trie with ${nodes.size} nodes")

        val buffer = java.nio.ByteBuffer.allocate(512 * 1024)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)

        buffer.put("TRIE".toByteArray())
        buffer.put(1)
        buffer.putInt(nodes.size)

        for (i in nodes.indices) {
            val children = nodes[i]
            buffer.put(children.size.toByte())
            for ((char, childIndex) in children) {
                buffer.put(char.code.toByte())
                buffer.putInt(childIndex)
            }

            val word = nodeWords[i]
            buffer.put(if (word != null) 1 else 0)
            if (word != null) {
                val bytes = word.toByteArray(Charsets.UTF_8)
                buffer.put(bytes.size.toByte())
                buffer.put(bytes)
                buffer.putInt(nodeFreqs[i])
            }
        }

        val data = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(data)
        outputFile.writeBytes(data)

        println("Wrote ${data.size} bytes (${data.size / 1024}KB) to ${outputFile.name}")
    }
}

// kaldi-native-fbank + kissfft are fetched into jni/third_party/knf (gitignored)
// at build time, then consumed by CMake as local source dirs. Reference:
//   - KNF: https://github.com/csukuangfj/kaldi-native-fbank (Apache-2.0)
//   - kissfft: https://github.com/mborgerding/kissfft (BSD)
val downloadKnf by tasks.registering {
    val knfVersion = "1.22.3"
    val kissfftTag = "131.2.0"
    val knfRoot = file("src/main/jni/third_party/knf")
    val knfSrc = file("$knfRoot/kaldi-native-fbank-${knfVersion}")
    val kissfftSrc = file("$knfRoot/kissfft")

    val knfCmake = file("$knfSrc/CMakeLists.txt")
    val kissfftCmake = file("$kissfftSrc/CMakeLists.txt")
    // 用关键文件而非目录做 up-to-date 判断：目录可能残留残缺/不完整内容
    //（例如被 CI 缓存恢复），但缺少 CMakeLists.txt 会导致 CMake 配置失败。
    outputs.file(knfCmake)
    outputs.file(kissfftCmake)

    doLast {
        fun isZip(f: File): Boolean {
            return try {
                val b = f.readBytes()
                b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4b.toByte() &&
                    b[2] == 0x03.toByte() && b[3] == 0x04.toByte()
            } catch (_: Exception) {
                false
            }
        }

        // Download and verify a valid zip, retrying a few times (github archive
        // can return an error page that downloadWithMirrors treats as success).
        fun downloadZip(urls: List<String>, target: File, workDir: File, desc: String): Boolean {
            repeat(5) {
                if (downloadWithMirrors(urls, target, workDir, desc)) {
                    if (target.exists() && isZip(target)) return true
                    target.delete()
                }
            }
            return false
        }

        knfRoot.mkdirs()

        if (!knfCmake.exists()) {
            knfSrc.deleteRecursively()
            val zip = File(buildDir, "kaldi-native-fbank-${knfVersion}.zip")
            val url = "https://github.com/csukuangfj/kaldi-native-fbank/archive/refs/tags/v${knfVersion}.zip"
            if (!downloadZip(listOf(url), zip, buildDir, "kaldi-native-fbank")) {
                throw GradleException("Failed to download a valid kaldi-native-fbank zip")
            }
            copy {
                from(zipTree(zip))
                into(knfRoot)
            }
            if (!knfSrc.exists()) {
                throw GradleException("kaldi-native-fbank extract dir not found under $knfRoot")
            }
            println("kaldi-native-fbank downloaded to $knfSrc")
        }

        if (!kissfftCmake.exists()) {
            kissfftSrc.deleteRecursively()
            val kzip = File(buildDir, "kissfft.zip")
            val kurl = "https://github.com/mborgerding/kissfft/archive/refs/tags/${kissfftTag}.zip"
            if (!downloadZip(listOf(kurl), kzip, buildDir, "kissfft")) {
                throw GradleException("Failed to download a valid kissfft zip")
            }
            copy {
                from(zipTree(kzip))
                into(knfRoot)
            }
            val extracted = file("$knfRoot/kissfft-${kissfftTag}")
            if (extracted.exists()) extracted.renameTo(kissfftSrc)
            if (!kissfftSrc.exists()) {
                throw GradleException("kissfft extract dir not found under $knfRoot")
            }
            println("kissfft downloaded to $kissfftSrc")
        }
    }
}

// 离线 ASR 已集成进主版本，KNF 源码始终需要，preBuild 直接依赖 downloadKnf。

// ── librime native 构建输入保障（修"改了 C++ 代码 Run 不重编"）──
// 1) librime-t9 权威源码在顶层 jni/librime-t9；构建期把它符号链接为
//    plugins/librime-t9（物理单份），既让 librime 插件构建能找到它，
//    又避免拷贝副本被改错/被静默覆盖。Rime.cmake 在 configure 时也会
//    幂等创建该链接，这里额外保障（如链接被误删时）。
val syncT9Plugin by tasks.registering {
    val authoritative = file("src/main/jni/librime-t9")
    val mirror = file("src/main/jni/librime/plugins/librime-t9")
    val expectedTarget = Paths.get("../../librime-t9")
    inputs.dir(authoritative)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional(true)
    doLast {
        val link = mirror.toPath()
        if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == expectedTarget) {
            return@doLast
        }
        if (Files.isSymbolicLink(link)) {
            Files.delete(link)
        } else if (mirror.exists()) {
            mirror.deleteRecursively()
        }
        Files.createSymbolicLink(link, expectedTarget)
    }
}

// 2) 把 librime 核心/插件源码目录声明为 native 构建任务的显式输入：
//    AGP 对 CMake 任务的 up-to-date 判断不覆盖 submodule 源文件，
//    缺失该输入时 Android Studio 的 Run 会直接跳过 ninja，
//    改动（尤其 librime/src 核心改动）不会进 APK。
//    声明后：源码一变任务必然执行，ninja 按依赖增量重编，仍然很快。
tasks.configureEach {
    if (name.startsWith("buildCMake")) {
        dependsOn(syncT9Plugin)
        inputs.dir("src/main/jni/librime/src")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .optional(true)
        inputs.dir("src/main/jni/librime-t9")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .optional(true)
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadOnnx)
    dependsOn(downloadKnf)
    dependsOn(buildTrie)
}