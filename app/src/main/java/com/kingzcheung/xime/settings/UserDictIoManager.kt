package com.kingzcheung.xime.settings

import android.content.Context
import android.net.Uri
import com.kingzcheung.xime.rime.RimeEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 用户词库访问：输入时自动积累的 `<词典名>.userdb`（librime leveldb 用户词典）。
 *
 * 与「方案词库」（随方案分发的静态 `.dict.yaml`）是两类数据。librime 没有
 * "读词条"的 C 接口，只能经 JNI 用内部 DbSource 遍历并转成文本码表文本
 * （见 `rime_jni.cc::readUserDictText`）；导出 / 导入 / 增 / 删则**共用引擎自带的同一条通道**
 * （`UserDictManager::Export/Import` + `UserDbImporter`）—— 与 PC 端（小狼毫/鼠须管）
 * 同一实现，生成的 `.txt` 码表可互通。单条增删就是把一行码表交给 Import，不必动 native。
 */
object UserDictIoManager {

    /** 一本用户词库。 */
    data class UserDict(
        val name: String,
        /** `<词典名>.userdb` 目录最近修改时间（毫秒；取不到为 0）。 */
        val lastModified: Long
    )

    /** 导入文件大小上限：真人词库码表只有几十 KB，超过就是选错文件了。 */
    internal const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

    private const val USERDB_EXTENSION = ".userdb"
    private const val EXPORT_TEMP_NAME = "userdict_export.txt"
    private const val IMPORT_TEMP_NAME = "userdict_import.txt"
    /** 单条增删用的临时码表（与整库导入分开，避免互相覆盖）。 */
    private const val EDIT_TEMP_NAME = "userdict_edit.txt"

    /**
     * 列出本机用户词库，按名称升序。
     *
     * 判定规则与 librime `UserDictManager::GetUserDictList` 一致：扫描用户数据目录下
     * 以 `.userdb` 结尾的条目，去掉扩展名即为词典名。这里走文件系统而非引擎接口，
     * 引擎尚未初始化时也能可靠列出。
     */
    fun list(context: Context): List<UserDict> {
        val rimeDir = SchemaManager.getRimeDir(context)
        val files = rimeDir.listFiles() ?: return emptyList()
        return files
            .filter { it.name.endsWith(USERDB_EXTENSION) }
            .map { UserDict(it.name.removeSuffix(USERDB_EXTENSION), it.lastModified()) }
            .sortedBy { it.name }
    }

    /**
     * 读取一本用户词库的词条（文本码表格式：`词<TAB>码<TAB>频率`，只读）。
     * 空列表表示该库暂无词条或读取失败（打不开、非 userdb）。
     */
    fun readEntries(dictName: String): List<DictEntry> {
        val text = RimeEngine.getInstance().readUserDictText(dictName)
        if (text.isBlank()) return emptyList()
        return DictionaryHelper.parseCodeTable(text)
    }

    /** 导出时的建议文件名：`<词典名>.txt`（Rime 的文本码表就是 `.txt`，如 custom_phrase.txt）。 */
    fun exportFileName(dictName: String): String = "$dictName.txt"

    /**
     * 导出到系统文件选择器选定的位置。
     *
     * librime 的 Export 只接受真实路径，故先写 cacheDir 临时文件，再把字节复制进 uri；
     * 无论成败都删临时文件。已标记删除的条目不会被写出。
     *
     * @return 导出的条目数
     */
    fun exportTo(context: Context, dictName: String, uri: Uri): Result<Int> = runCatching {
        val temp = File(context.cacheDir, EXPORT_TEMP_NAME)
        try {
            val count = RimeEngine.getInstance().exportUserDict(dictName, temp.absolutePath)
            if (count < 0) throw IOException("导出失败：打不开「$dictName」用户词库")
            context.contentResolver.openOutputStream(uri)?.use { output ->
                temp.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("无法写入所选文件")
            count
        } finally {
            temp.delete()
        }
    }

    /**
     * 从系统文件选择器选定的文件导入进指定用户词库。
     *
     * **合并**语义（librime `UserDictImporter`）：同词条取较大频率、负频率视为删除标记，
     * 不会清空原有条目。导入前先用 [validateImportText] 挡住明显选错的文件 ——
     * 导入会真正改动用户词库，不能把二进制垃圾写进去。
     *
     * @return 成功解析并写入的条目数
     */
    fun importFrom(context: Context, dictName: String, uri: Uri): Result<Int> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            readAllBytesLimited(input, MAX_IMPORT_BYTES)
        } ?: throw IOException("无法读取所选文件")
        if (bytes.size > MAX_IMPORT_BYTES) {
            throw IOException("文件过大（超过 ${MAX_IMPORT_BYTES / 1024 / 1024} MB），不像是码表")
        }
        val text = bytes.toString(Charsets.UTF_8)
        validateImportText(text)?.let { throw IOException(it) }

        val temp = File(context.cacheDir, IMPORT_TEMP_NAME)
        try {
            temp.writeText(text, Charsets.UTF_8)
            val count = RimeEngine.getInstance().importUserDict(dictName, temp.absolutePath)
            if (count < 0) throw IOException("导入失败：引擎拒绝了这个文件")
            count
        } finally {
            temp.delete()
        }
    }

    /** 新增词条时的默认频率（librime 首次造词即 commits=1）。 */
    internal const val DEFAULT_COMMITS = 1

    /**
     * 删除标记：码表里频率为负，librime 的 `UserDbImporter::Put` 就把该条目标记为已删除。
     * 记录仍留在库里（不是物理删除），用户之后再输入并选中它会被"复活"。
     */
    internal const val DELETED_COMMITS = -1

    /** 新增词条的入库输入（已校验、已规整）。 */
    data class EntryInput(val word: String, val code: String, val commits: Int)

    /** 新增词条输入的校验结果。 */
    internal sealed interface EntryInputCheck {
        /** 通过，可直接写入。 */
        data class Ok(val entry: EntryInput) : EntryInputCheck

        /** 还没填完（词或编码为空）：只用来禁用"确定"，不必弹红字。 */
        data object Incomplete : EntryInputCheck

        /** 填了但格式不对，[message] 是给用户看的说明。 */
        data class Error(val message: String) : EntryInputCheck
    }

    /**
     * 校验"新增词条"的输入。词/码非空且不含制表符与换行（否则会破坏码表行的结构），
     * 频率留空取 [DEFAULT_COMMITS]，填了必须是正整数。纯函数。
     */
    internal fun checkEntryInput(word: String, code: String, weightText: String): EntryInputCheck {
        val w = word.trim()
        val c = code.trim()
        if (w.isEmpty() || c.isEmpty()) return EntryInputCheck.Incomplete
        if (w.any { it == '\t' || it == '\n' || it == '\r' }) {
            return EntryInputCheck.Error("词不能含制表符或换行")
        }
        if (c.any { it == '\t' || it == '\n' || it == '\r' }) {
            return EntryInputCheck.Error("编码不能含制表符或换行")
        }
        val text = weightText.trim()
        val commits = if (text.isEmpty()) DEFAULT_COMMITS else (text.toIntOrNull() ?: 0)
        if (commits <= 0) return EntryInputCheck.Error("频率要填正整数（留空即 1）")
        return EntryInputCheck.Ok(EntryInput(w, c, commits))
    }

    /**
     * 新增一条词条。
     *
     * 复用「导入文本码表」那条通道：写一行 `词<TAB>码<TAB>频率`，交给 librime 的
     * `UserDictManager::Import`。这样造出的键与引擎自己写的完全一致 —— userdb 的键是
     * `码 + 空格 + <TAB> + 词`（见 `TableDb::format` 的 parser：`trim(code) + " \t" + 词`，
     * 与 `UserDictionary::Lookup` 里 `key[len] == ' '` 的判定对应），而且**不需要改 native**。
     *
     * 合并语义（`UserDbImporter::Put`）：同键取较大频率；该条目此前若被标记删除过，
     * 这次写入会把频率改回正数，也就是"复活"。所以频率调不低（要调低得先删再写）。
     */
    fun addEntry(context: Context, dictName: String, input: EntryInput): Result<Int> =
        applyCodeTable(context, dictName, codeTableText(input.word, input.code, input.commits))

    /**
     * 删除一条词条 —— 写一行频率为 [DELETED_COMMITS] 的码表，由 librime 标记为已删除
     * （tombstone，不是物理删除）：列表、导出与候选都不会再出现它，但记录仍在库里。
     *
     * 与 PC 端一致，被标记删除的词之后若再次被输入并选中就会"复活"
     * （`UserDictionary::UpdateEntry` 里 `if (v.commits < 0) v.commits = -v.commits`），
     * 所以给用户的措辞不能写成"永久删除"。
     */
    fun deleteEntry(context: Context, dictName: String, word: String, code: String): Result<Int> =
        applyCodeTable(context, dictName, codeTableText(word, code, DELETED_COMMITS))

    /** 单行文本码表：`词<TAB>码<TAB>频率`。 */
    internal fun codeTableText(word: String, code: String, commits: Int): String =
        "$word\t$code\t$commits\n"

    /**
     * 把单行码表写进指定词库：与整库导入共用 `importUserDict`，只是文件里只有一行。
     *
     * @return 引擎写入的条目数（正常为 1）；<= 0 说明引擎没接受这一行或打不开词库
     */
    private fun applyCodeTable(context: Context, dictName: String, text: String): Result<Int> =
        runCatching {
            val temp = File(context.cacheDir, EDIT_TEMP_NAME)
            try {
                temp.writeText(text, Charsets.UTF_8)
                val count = RimeEngine.getInstance().importUserDict(dictName, temp.absolutePath)
                if (count <= 0) throw IOException("写入失败：引擎没有接受这条词条")
                count
            } finally {
                temp.delete()
            }
        }

    /**
     * 判断文本是否像 Rime 文本码表（`词<TAB>码[<TAB>频率]`）。
     * 返回 null 表示通过，否则返回给用户看的错误说明。
     *
     * 规则刻意宽松（`#` 注释行与无制表符的行都忽略，方案 `.dict.yaml` 的数据段也能导入），
     * 只挡住"空文件 / 二进制文件 / 完全没有码表行"这三种典型误选。
     */
    internal fun validateImportText(text: String): String? {
        if (text.isBlank()) return "文件是空的"
        if (text.contains('\u0000')) return "不是文本文件（含有空字节），请选择导出的 .txt 码表"
        val entries = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .count { line ->
                val parts = line.split('\t')
                parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()
            }
        if (entries == 0) return "没找到「词<制表符>码」格式的词条，请选择导出的 .txt 码表"
        return null
    }

    /**
     * 选默认展示的词库：优先当前方案引用的词典（`wubi86` 方案 → `wubi86.userdb`），
     * 该词典没有用户词库时退回列表第一本（[dictNames] 已按名称升序）。纯函数。
     */
    internal fun pickDefaultDict(dictNames: List<String>, preferred: String?): String? {
        if (dictNames.isEmpty()) return null
        return if (preferred != null && preferred in dictNames) preferred else dictNames.first()
    }

    /**
     * 读取至多 [limit] 字节；若源更长，则只多读 1 字节返回（`size > limit` 即超限），
     * 由调用方判超。手写而不用 `InputStream.readNBytes`：后者要 API 33。
     */
    internal fun readAllBytesLimited(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, minOf(read, limit + 1 - buffer.size()))
            if (buffer.size() > limit) break
        }
        return buffer.toByteArray()
    }
}