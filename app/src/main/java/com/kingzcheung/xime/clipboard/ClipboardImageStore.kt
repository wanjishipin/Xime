package com.kingzcheung.xime.clipboard

import java.io.File

/**
 * 剪贴板图片文件存储（内容寻址）。
 *
 * 设计要点：
 * - **内容寻址命名**：`<sha256>.<ext>`，同图重复复制天然去重、天然幂等（也便于将来同步 blob 对齐命名）。
 * - **纯逻辑可测**：本类只依赖 [File] 与纯数据 [ClipboardItem]，不引用任何 Android API，
 *   可在 JVM 单测中直接构造临时目录验证配额/淘汰语义。
 * - **保护集**：当前系统剪贴板仍指向本应用文件的图片不得淘汰，否则粘贴方读取会失败
 *   （由调用方传入 [ClipboardItem.imagePath] 集合）。
 *
 * 目录：`files/clipboard_images/`（FileProvider `files-path path="."` 已覆盖，无需改 filepaths.xml）。
 */
class ClipboardImageStore(private val rootDir: File) {

    /** 采集拒收原因（[companion.rejectReason] 的返回值）。 */
    enum class RejectReason {
        /** 字节数超过 [MAX_IMAGE_BYTES]。 */
        TOO_LARGE_BYTES,

        /** 宽或高超过 [MAX_IMAGE_EDGE]。 */
        TOO_LARGE_DIMENSION,
    }

    companion object {
        const val DIR_NAME = "clipboard_images"

        /**
         * 以下常量中，只有单张字节上限在 Phase 2 起可由设置页覆盖（作为默认值）；
         * 最长边 / 保留数量 / 总容量为**内置限制**，不提供设置项。
         * 本类只作为纯逻辑与默认值来源，不读取任何 Android API（保持 JVM 可测）。
         */

        /** 单张图片字节上限：超过即**拒收**（不压缩、不入库，并 Toast 提示用户）。 */
        const val MAX_IMAGE_BYTES = 5L * 1024 * 1024

        /** 单张图片最长边上限（像素）：宽或高任一超过即**拒收**（同上，不做下采样）。 */
        const val MAX_IMAGE_EDGE = 4096

        /** 图片目录总容量上限。 */
        const val MAX_TOTAL_BYTES = 100L * 1024 * 1024

        /** 图片条目数量上限（文本条目上限 MAX_ITEMS 独立）。 */
        const val MAX_IMAGES = 200

        /** 支持采集的图片 MIME → 扩展名。 */
        private val MIME_TO_EXT = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/jpg" to "jpg",
            "image/gif" to "gif",
            "image/webp" to "webp",
            "image/bmp" to "bmp",
            "image/x-ms-bmp" to "bmp",
            "image/heic" to "heic",
            "image/heif" to "heic",
            "image/avif" to "avif",
        )

        private val EXT_TO_MIME = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "bmp" to "image/bmp",
            "heic" to "image/heic",
            "avif" to "image/avif",
        )

        fun rootDirOf(filesDir: File): File = File(filesDir, DIR_NAME)

        /**
         * 绝对文件 → `files/` 下的相对路径（仅限本存储目录内，越界返回 null）。
         * 用于回写系统剪贴板时登记"淘汰保护集"。
         */
        fun relativePathOf(filesDir: File, file: File): String? {
            val root = rootDirOf(filesDir).canonicalFile
            val target = file.canonicalFile
            val rootPath = root.path + File.separator
            if (!target.path.startsWith(rootPath)) return null
            return DIR_NAME + "/" + target.path.removePrefix(rootPath)
        }

        /** MIME → 扩展名；非支持类型返回 null。 */
        fun extensionForMime(mime: String?): String? {
            val key = mime?.trim()?.lowercase()?.substringBefore(';') ?: return null
            return MIME_TO_EXT[key]
        }

        /** 扩展名 → MIME（存储与同步回写用）。 */
        fun mimeForExtension(extension: String?): String? =
            EXT_TO_MIME[extension?.trim()?.lowercase()]

        /**
         * 魔数嗅探图片类型（部分应用的 ClipData 不声明 MIME，或声明为 `application/octet-stream`）。
         * 返回扩展名；无法识别返回 null。
         */
        fun sniffExtension(bytes: ByteArray): String? {
            if (bytes.size < 12) return null
            fun u8(i: Int) = bytes[i].toInt() and 0xFF
            return when {
                // PNG: 89 50 4E 47 0D 0A 1A 0A
                u8(0) == 0x89 && u8(1) == 0x50 && u8(2) == 0x4E && u8(3) == 0x47 -> "png"
                // JPEG: FF D8 FF
                u8(0) == 0xFF && u8(1) == 0xD8 && u8(2) == 0xFF -> "jpg"
                // GIF: "GIF8"
                u8(0) == 0x47 && u8(1) == 0x49 && u8(2) == 0x46 && u8(3) == 0x38 -> "gif"
                // WebP: "RIFF" .... "WEBP"
                u8(0) == 0x52 && u8(1) == 0x49 && u8(2) == 0x46 && u8(3) == 0x46 &&
                    u8(8) == 0x57 && u8(9) == 0x45 && u8(10) == 0x42 && u8(11) == 0x50 -> "webp"
                // BMP: "BM"
                u8(0) == 0x42 && u8(1) == 0x4D -> "bmp"
                // HEIF/AVIF: offset 4 = "ftyp", brand 在 8..11
                u8(4) == 0x66 && u8(5) == 0x74 && u8(6) == 0x79 && u8(7) == 0x70 -> {
                    val brand = String(bytes, 8, 4, Charsets.US_ASCII)
                    when (brand) {
                        "heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1" -> "heic"
                        "avif", "avis" -> "avif"
                        else -> null
                    }
                }
                else -> null
            }
        }

        /**
         * 从 MIME 与字节内容综合判定扩展名：MIME 优先（可信度更高），
         * MIME 缺失/不支持时回退魔数嗅探。返回 null 表示"不是可采集的图片"。
         */
        fun resolveExtension(mime: String?, bytes: ByteArray): String? =
            extensionForMime(mime) ?: sniffExtension(bytes)

        /**
         * 采集准入判定（纯函数，便于单测）：**任一**超限即拒收。
         *
         * 本应用不压缩图片——超限直接不入库，由调用方提示用户（取舍见
         * docs/clipboard-image-plan.md 决策 D8：宁可不采集，也不擅自改变原图内容/画质）。
         * 尺寸解码失败（width/height 为 0）时只按字节数判定，不误杀。
         *
         * @param maxBytes 单张字节上限，默认 [MAX_IMAGE_BYTES]；设置页可调（Phase 2）
         */
        fun rejectReason(
            sizeBytes: Long,
            width: Int,
            height: Int,
            maxBytes: Long = MAX_IMAGE_BYTES,
        ): RejectReason? = when {
            sizeBytes > maxBytes -> RejectReason.TOO_LARGE_BYTES
            width > MAX_IMAGE_EDGE || height > MAX_IMAGE_EDGE -> RejectReason.TOO_LARGE_DIMENSION
            else -> null
        }

        /**
         * 计算应淘汰的图片条目（纯函数）。
         *
         * 规则：跳过置顶（[ClipboardItem.isPinned]）与保护集（[protectedPaths]，如当前系统剪贴板
         * 仍指向的文件）；其余按「已消费优先 → 时间戳升序」依次淘汰，直到满足数量与容量上限。
         * 返回空列表表示无需淘汰。
         */
        fun selectEvictions(
            images: List<ClipboardItem>,
            protectedPaths: Set<String> = emptySet(),
            maxImages: Int = MAX_IMAGES,
            maxTotalBytes: Long = MAX_TOTAL_BYTES,
        ): List<ClipboardItem> {
            val candidates = images
                .filter { it.isImage && it.imagePath.isNotEmpty() }
                .filter { !it.isPinned && it.imagePath !in protectedPaths }
                .sortedWith(compareByDescending<ClipboardItem> { it.consumed }.thenBy { it.timestamp })

            var count = images.count { it.isImage && it.imagePath.isNotEmpty() }
            var bytes = images.filter { it.isImage }.sumOf { it.sizeBytes }
            val evicted = mutableListOf<ClipboardItem>()
            for (candidate in candidates) {
                if (count <= maxImages && bytes <= maxTotalBytes) break
                evicted.add(candidate)
                count -= 1
                bytes -= candidate.sizeBytes
            }
            return evicted
        }
    }

    fun fileFor(hash: String, extension: String): File = File(rootDir, "$hash.$extension")

    /** 相对 `files/` 的路径（入库用），与 [absoluteFile] 互逆。 */
    fun relativePath(hash: String, extension: String): String = "$DIR_NAME/$hash.$extension"

    /** 相对路径 → 绝对文件（`files/` 根由 [rootDir] 的父目录体现）。 */
    fun absoluteFile(relativePath: String): File = File(rootDir.parentFile, relativePath)

    /** 文件是否落在本存储目录内（防越界路径）。 */
    private fun isInside(file: File): Boolean =
        file.canonicalPath.startsWith(rootDir.canonicalPath + File.separator)

    /**
     * 落盘图片字节；已存在同 hash 文件时直接复用（内容寻址 ⇒ 内容一致）。
     * @param maxBytes 单张字节上限（默认常量，实际值由设置页可配）
     * @return 相对路径；写入失败返回 null。
     */
    fun save(
        bytes: ByteArray,
        hash: String,
        extension: String,
        maxBytes: Long = MAX_IMAGE_BYTES,
    ): String? {
        // 兜底重复校验：调用方应先过 rejectReason，这里避免漏改路径把超大图写进磁盘
        if (bytes.isEmpty() || rejectReason(bytes.size.toLong(), 0, 0, maxBytes = maxBytes) != null) return null
        val relative = relativePath(hash, extension)
        val target = absoluteFile(relative)
        if (!isInside(target)) return null
        return try {
            if (target.exists() && target.length() == bytes.size.toLong()) {
                return relative
            }
            rootDir.mkdirs()
            // 先写临时文件再原子改名：避免半截文件被当成合法图片（含断电/进程被杀）
            val tmp = File(rootDir, "$hash.$extension.tmp")
            tmp.outputStream().use { it.write(bytes) }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            relative
        } catch (e: Exception) {
            null
        }
    }

    /** 删除相对路径对应的文件；不存在视为成功。 */
    fun delete(relativePath: String): Boolean {
        if (relativePath.isEmpty()) return false
        val file = absoluteFile(relativePath)
        if (!isInside(file)) return false
        return try {
            !file.exists() || file.delete()
        } catch (e: Exception) {
            false
        }
    }

    /** 当前目录占用字节数（统计页用）。 */
    fun usedBytes(): Long =
        rootDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    /**
     * 清理数据库无记录的残留文件（含 .tmp 半成品）。
     * @param knownRelativePaths 数据库当前引用的相对路径集合
     * @return 删除的文件数
     */
    fun cleanupOrphans(knownRelativePaths: Set<String>): Int {
        val files = rootDir.listFiles() ?: return 0
        var removed = 0
        for (file in files) {
            if (!file.isFile) continue
            val relative = "$DIR_NAME/${file.name}"
            val isTmp = file.name.endsWith(".tmp")
            if (!isTmp && relative in knownRelativePaths) continue
            if (file.delete()) removed++
        }
        return removed
    }
}