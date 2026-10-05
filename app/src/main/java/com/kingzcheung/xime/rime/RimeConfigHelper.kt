package com.kingzcheung.xime.rime

import com.kingzcheung.xime.util.FileLogger
import android.content.Context
import android.util.Log
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.SchemaManifestManager
import com.kingzcheung.xime.settings.MarketVersionStore
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object RimeConfigHelper {
    private const val TAG = "RimeConfigHelper"
    private const val ASSETS_RIME_DIR = "rime"
    /** app 固定的 default.custom.yaml 模板（assets 根，与 xime.yaml 并列，不随 submodule 分发）。 */
    private const val ASSETS_DEFAULT_CUSTOM = "default.custom.yaml"
    private const val BUFFER_SIZE = 8192

    /** 市场索引中随 app 发布的内置默认方案集条目 id（见 rimes/index.yaml 的 `builtin`）。 */
    private const val BUILTIN_MARKET_ID = "builtin"
    /** 内置方案集的初始占位版本：`0.0.0` 保证不等于任何真实 git tag，从而在市场提示「更新」。 */
    private const val BUILTIN_PLACEHOLDER_VERSION = "0.0.0"

    /** 部署互斥：Application 预初始化与输入法服务初始化可能并发触发部署，串行化避免重复/并发全量编译。 */
    private val deploymentLock = Any()
    
    suspend fun initializeRimeDataAsync(context: Context): Pair<String, String> {
        val rimeDir = File(context.filesDir, "rime")
        
        // 迁移旧目录结构 (rime/shared/ + rime/user/) → 单一 rime/ 目录
        migrateOldStructure(context, rimeDir)
        
        // 迁移旧版 market 目录（rime/market/ → market/）
        migrateOldMarketDir(context)
        
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, rimeDir)
        // F1: assets 会用内置 default.yaml 覆盖，这里把启用方案重新写回 schema_list
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        // 为所有启用方案打个人词库补丁
        PersonalDictManager.ensureSchemaPacks(context)
        // 不再在初始化阶段删 build：build 是否重建统一由 ensureDeployment()
        // 按增量优先策略决定，避免配置变化即全量重编译（60MB 词库持锁 30s+）。

        return Pair(rimeDir.absolutePath, rimeDir.absolutePath)
    }

    /**
     * 统一部署入口（进程内互斥）：
     * - 部署 hash 一致 → build 已是最新，对齐 deploymentDone 标记，跳过编译；
     * - hash 缺失/不一致 → 优先增量维护（librime 按文件时间戳只编译变更），
     *   仅当 build 缺失/为空或增量失败时才清空全量编译。
     *
     * 必须由调用方保证 engine 已 initialize（deploy() 未初始化时返回 false）。
     * 该入口被 Application 预初始化与输入法服务共享，配合 deploymentLock
     * 避免两者并发触发两次全量编译。
     */
    fun ensureDeployment(context: Context): Boolean {
        synchronized(deploymentLock) {
            val currentHash = computeDeploymentHash(context)
            if (currentHash.isNotEmpty() && currentHash == SettingsPreferences.getDeploymentHash(context)) {
                SettingsPreferences.setDeploymentDone(context, true)
                return true
            }
            Log.i(TAG, "Deployment hash mismatch or missing")
            val buildDir = File(context.filesDir, "rime/build")
            val buildExists = buildDir.exists() && buildDir.listFiles()?.isNotEmpty() == true
            val engine = RimeEngine.getInstance()
            val deployed: Boolean
            if (buildExists) {
                // build 已就位但配置有变化：增量维护，只编译变更的 schema/dict，
                // 避免 custom.yaml 补丁等小幅改动触发 60MB 词库全量重编译（持锁 30s+）。
                Log.i(TAG, "Build exists, running incremental maintenance")
                if (engine.deployIncremental()) {
                    deployed = true
                } else {
                    FileLogger.w(TAG, "Incremental maintenance failed, falling back to full deploy")
                    buildDir.deleteRecursively()
                    buildDir.mkdirs()
                    deployed = engine.deploy()
                }
            } else {
                Log.i(TAG, "Build directory missing or empty, running full deploy")
                buildDir.mkdirs()
                deployed = engine.deploy()
            }
            if (deployed) {
                storeDeploymentHash(context)
                SettingsPreferences.setDeploymentDone(context, true)
                return true
            }
            return false
        }
    }
    
    fun initializeRimeData(context: Context): Pair<String, String> {
        val rimeDir = File(context.filesDir, "rime")
        
        migrateOldStructure(context, rimeDir)
        
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, rimeDir)
        // F1: 同步初始化路径也写回 default.yaml 的 schema_list
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        runBlocking { PersonalDictManager.ensureSchemaPacks(context) }
        // build 重建统一由 ensureDeployment() 增量优先决策，此处不删 build
        
        return Pair(rimeDir.absolutePath, rimeDir.absolutePath)
    }
    
    fun storeDeploymentHash(context: Context) {
        val hash = computeDeploymentHash(context)
        if (hash.isNotEmpty()) {
            SettingsPreferences.setDeploymentHash(context, hash)
        }
    }

    /**
     * 部署产物完整性检查（半成品检测）：librime 的 table/prism 产物文件头是固定
     * magic（"Rime::Table/" / "Rime::Prism/"，Metadata.format 位于文件偏移 0）。
     * 部署进行中进程被杀会留下空文件或截断的半成品——其 mtime 比源文件新，
     * librime 增量维护会视为"已是最新"而跳过重编，导致该方案查询永远零命中
     * （症状：九键方案切换正常、按键进 buffer，但候选恒空、候选栏恒 IDLE）。
     */
    internal fun isBrokenBuildArtifact(file: File): Boolean {
        val expectedMagic = when {
            file.name.endsWith(".prism.bin") -> "Rime::Prism/"
            file.name.endsWith(".table.bin") -> "Rime::Table/"
            // 未知类型（如 reverse.bin、日志）不判损坏，避免误删触发部署循环
            else -> return false
        }
        if (!file.isFile || file.length() == 0L) return true
        if (file.length() < expectedMagic.length) return true
        return try {
            java.io.FileInputStream(file).use { input ->
                val head = ByteArray(expectedMagic.length)
                var read = 0
                while (read < head.size) {
                    val n = input.read(head, read, head.size - read)
                    if (n < 0) return true
                    read += n
                }
                !String(head, Charsets.US_ASCII).startsWith(expectedMagic)
            }
        } catch (_: IOException) {
            // 读不了不臆断损坏，交由 librime 运行时自检兜底
            false
        }
    }

    fun isDeploymentComplete(context: Context): Boolean {
        val rimeDir = File(context.filesDir, "rime")
        val buildDir = File(rimeDir, "build")
        if (!buildDir.exists()) return false

        val enabledSchemas = SchemaManager.getEnabledSchemas(context)
        if (enabledSchemas.isEmpty()) return false

        // 损坏产物统一扫描：*.prism.bin / *.table.bin 存在但 magic 不对（部署中断
        // 半成品）→ 删除并判定未完成。产物被删除后 librime 增量维护才会重建它；
        // 同时清 stored hash，避免 ensureDeployment 因 hash 一致而短路跳过重建。
        // 词典产物按 dictionary 名生成，可能与 schemaId 不同名（如 t9_pinyin 用
        // pinyin_simp 词典），故扫描整个 build 目录而非按方案枚举。
        buildDir.listFiles()?.forEach { artifact ->
            if (artifact.isFile && isBrokenBuildArtifact(artifact)) {
                artifact.delete()
                SettingsPreferences.setDeploymentHash(context, "")
                FileLogger.w(TAG, "Broken build artifact removed: ${artifact.name}")
                return false
            }
        }

        for (schemaId in enabledSchemas) {
            if (!File(buildDir, "$schemaId.prism.bin").exists() &&
                !File(buildDir, "$schemaId.schema.yaml").exists()) {
                return false
            }
        }

        val currentHash = computeDeploymentHash(context)
        if (currentHash.isEmpty()) return false

        val storedHash = SettingsPreferences.getDeploymentHash(context)
        if (storedHash.isEmpty()) {
            SettingsPreferences.setDeploymentHash(context, currentHash)
            return true
        }

        if (currentHash != storedHash) {
            return false
        }

        return true
    }

    private fun fileUpdateDigest(digest: java.security.MessageDigest, file: File) {
        if (!file.exists()) return
        java.io.FileInputStream(file).use { input ->
            java.security.DigestInputStream(input, digest).use { dis ->
                val buffer = ByteArray(8192)
                while (dis.read(buffer) != -1) { }
            }
        }
    }

    private fun computeDeploymentHash(context: Context): String {
        val rimeDir = File(context.filesDir, "rime")
        val digest = java.security.MessageDigest.getInstance("SHA-256")

        val enabledSchemas = SchemaManager.getEnabledSchemas(context)
        for (schemaId in enabledSchemas.sorted()) {
            val schemaFile = File(rimeDir, "$schemaId.schema.yaml")
            if (schemaFile.exists()) {
                digest.update(schemaId.toByteArray())
                fileUpdateDigest(digest, schemaFile)
            }
            val customFile = File(rimeDir, "$schemaId.custom.yaml")
            if (customFile.exists()) {
                fileUpdateDigest(digest, customFile)
            }
            // merged dict 由 app 生成、librime 实际编译，计入 hash 以便其变更（如转发器展开修复）触发重编译
            val mergedDictFile = File(rimeDir, "${schemaId}_merged.dict.yaml")
            if (mergedDictFile.exists()) {
                digest.update("${schemaId}_merged".toByteArray())
                fileUpdateDigest(digest, mergedDictFile)
            }
        }

        // 所有词典文件（内置词典与个人词库）纳入 hash：
        // 否则词典新增/变更（如 pinyin_simp.dict.yaml）不会改变 hash，
        // build 目录不重建、不重新部署，导致 table.bin 缺失（运行时反复报错）。
        rimeDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".dict.yaml") }
            ?.sortedBy { it.name }
            ?.forEach { dictFile ->
                digest.update(dictFile.name.toByteArray())
                fileUpdateDigest(digest, dictFile)
            }

        val defaultYaml = File(rimeDir, "default.yaml")
        if (defaultYaml.exists()) {
            digest.update("default".toByteArray())
            fileUpdateDigest(digest, defaultYaml)
        }

        // default.custom.yaml 变化（patch 基线修补/方案列表启停）也要触发重部署：
        // librime 编译各方案时经 DefaultConfigPlugin include default 的 menu 等节，
        // 且其 __build_info/timestamps 记录了 default.custom.yaml 的 mtime，
        // hash 失配 → 增量维护 → 各方案配置重编 → 新 page_size 编入产物全局生效。
        val defaultCustomYaml = File(rimeDir, "default.custom.yaml")
        if (defaultCustomYaml.exists()) {
            digest.update("default.custom".toByteArray())
            fileUpdateDigest(digest, defaultCustomYaml)
        }

        return digest.digest().joinToString("") { String.format("%02x", it) }
    }

    private fun copyAssetsToRimeDir(context: Context, targetDir: File): Boolean {
        // 判断 rime/ 是否已部署过任何方案（存在任意 <name>.schema.yaml）。
        // 全新安装时 rime/ 为空 → 全量复制内置默认方案与系统文件，保证开箱即用。
        // 一旦已部署过方案（内置旧版或从市场安装的第三方方案），app 更新时
        // 一律不再用内置 assets 覆盖，避免覆盖第三方同名文件造成污染；
        // 需要更新的方案统一走方案市场。此判定不依赖具体方案 id/文件名，天然鲁棒。
        val alreadyHasSchemas = targetDir.listFiles()
            ?.any { it.isFile && it.name.endsWith(".schema.yaml") } == true
        if (alreadyHasSchemas) {
            // 仅兜底确保 default.yaml 存在（librime 入口必需），缺失时补一份，不覆盖已有。
            ensureDefaultYaml(context, targetDir)
            // 默认方案强制更新（维护者决策）：默认方案随 app 发布、由维护者统一
            // 维护，升级时以 assets 为准覆盖用户目录残留（治老版本升级残留废弃
            // 配置——如引用已废弃 t9_translator 的旧 t9_pinyin 方案导致九键零候选）。
            // 仅覆盖 assets 清单内的文件（内容比对，不同才写），清单外的第三方
            // 方案一律跳过不处理。覆盖后文件内容变化使部署 hash 失配，后续
            // ensureDeployment 按增量优先策略只重编受影响方案。
            val updated = updateBuiltinAssets(context, targetDir)
            if (updated > 0) {
                Log.i(TAG, "Updated $updated builtin asset file(s)")
            }
            syncBuiltinDefaultCustom(context, targetDir)
            return false
        }
        val copied = try {
            copyAssetsRecursively(context, ASSETS_RIME_DIR, targetDir)
        } catch (e: IOException) {
            FileLogger.e(TAG, "Failed to copy assets", e)
            false
        }
        if (copied) {
            seedBuiltinPackageVersion(context)
        }
        syncBuiltinDefaultCustom(context, targetDir)
        return copied
    }

    /**
     * 把 app 固定的 assets/default.custom.yaml 同步/修补到用户 rime 目录。
     *
     * 背景：menu/page_size 等全局默认经 default.custom.yaml patch 进 default.yaml，
     * 再由 librime DefaultConfigPlugin 编入每个方案的编译产物。submodule 里的同名
     * 文件只在全新安装复制一次（*.custom.yaml 被排除出强制更新），且旧版
     * setEnabledSchemas 启停方案时会把整文件重写成只剩 schema_list 的空壳——
     * page_size patch 就此丢失，候选数在 5（引擎兜底）与 20（运行时内存覆盖，
     * 重启/部署后失效）之间漂移。
     *
     * 规则：文件缺失 → 复制模板；已存在 → 内容对齐 app 设置值
     * （[patchDefaultCustomContent]，.custom.yaml 可以被覆盖，不保留 PC 遗留值）。
     * 文件变化使部署 hash 失配，由 ensureDeployment 增量重编后全局生效。
     */
    private fun syncBuiltinDefaultCustom(context: Context, targetDir: File) {
        val target = File(targetDir, ASSETS_DEFAULT_CUSTOM)
        val pageSize = SettingsPreferences.getPageSize(context).coerceAtLeast(1)
        try {
            if (!target.exists()) {
                copyAssetFile(context, ASSETS_DEFAULT_CUSTOM, target)
                // 模板基线（20）与用户设置不一致时（如 slider 调过）以设置为准
                val aligned = patchDefaultCustomContent(target.readText(), pageSize)
                if (aligned != null) {
                    target.writeText(aligned)
                }
                return
            }
            val patched = patchDefaultCustomContent(target.readText(), pageSize) ?: return
            target.writeText(patched)
            Log.i(TAG, "Aligned ${target.name} menu/page_size=$pageSize")
        } catch (e: IOException) {
            FileLogger.e(TAG, "Failed to sync $ASSETS_DEFAULT_CUSTOM", e)
        }
    }

    /**
     * default.custom.yaml 的基线对齐（纯函数）：
     * 1. 移除抢占双拼编码键的 key_binder 绑定——`semicolon`/`apostrophe` 在微软双拼
     *    等方案中是合法编码键（`;` = ing，`'` 为音节分隔符），而 key_binder 优先级高于
     *    speller，has_menu 时绑定会拦截按键，按 `;` 直接选词上屏、无法组码（issue #884）；
     * 2. 把 page_size 强制对齐为 app 当前设置值。
     * 注意 page_size 只是磁盘配置基线——方案自带的 menu/page_size（内置与第三方
     * 方案多为 PC 遗留默认 5，不适配手机）经 librime MergeTree 语义压过 default
     * 层，运行时的实际生效靠 JNI 层 setPageSize 直接注入（rime_jni.cc）。
     * 无需变化时返回 null。
     */
    internal fun patchDefaultCustomContent(text: String, pageSize: Int): String? {
        val sep = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.lines()
        var changed = false
        val updated = lines.toMutableList()

        // 1. 清理与双拼编码键冲突的 key_binder 绑定（旧版模板/用户目录残留）
        val kept = updated.filterNot { isConflictingKeyBindingLine(it) }
        if (kept.size != updated.size) {
            updated.clear()
            updated.addAll(kept)
            changed = true
        }

        // 2. 对齐 menu/page_size
        val pageSizeIdx = updated.indexOfFirst { it.trimStart().startsWith("page_size:") }
        if (pageSizeIdx >= 0) {
            val raw = updated[pageSizeIdx].trimStart().removePrefix("page_size:")
                .substringBefore('#').trim()
            if (raw.toIntOrNull() != pageSize) {
                val indent = updated[pageSizeIdx].takeWhile { it == ' ' || it == '\t' }
                updated[pageSizeIdx] = "${indent}page_size: $pageSize"
                changed = true
            }
        } else {
            val patchIdx = updated.indexOfFirst { it.trim() == "patch:" }
            if (patchIdx >= 0) {
                updated.add(patchIdx + 1, "  menu:")
                updated.add(patchIdx + 2, "    page_size: $pageSize")
                changed = true
            }
        }

        return if (changed) updated.joinToString(sep) else null
    }

    /**
     * 判断一行是否为抢占分号/单引号的 key_binder 绑定（如
     * `- { when: has_menu, accept: semicolon, send: 2 }`）。这类绑定在双拼方案下
     * 会拦截 `;`（ing）/`'`（音节分隔符），必须从配置中移除。
     */
    private fun isConflictingKeyBindingLine(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("-") || trimmed.startsWith("#")) return false
        if (!trimmed.contains("send:")) return false
        return trimmed.contains("accept: semicolon") || trimmed.contains("accept: apostrophe")
    }

    /**
     * 全新安装复制内置默认方案集后，为市场条目 `builtin`（type=built-in 的默认方案包，
     * 一个压缩包里含多个 schema）写入一个初始占位版本号。
     *
     * 市场方案的版本号与具体 .schema.yaml 无关，是方案集所属 git 仓库的 tag（见
     * rimes/index.yaml 中 builtin 条目）。随 app 发布的内置方案集在运行时无法自报其
     * 对应的 git tag，因此写入一个不等于任何真实 tag 的占位值，使市场 `hasUpdate`
     * （installedVersion != currentVersion）成立，从而向用户提示「更新」，把默认方案
     * 从市场更新到带真实版本号的版本。仅当该条目尚无版本记录时写入。
     */
    private fun seedBuiltinPackageVersion(context: Context) {
        if (MarketVersionStore.getSchemeVersion(context, BUILTIN_MARKET_ID) == null) {
            MarketVersionStore.setSchemeVersion(context, BUILTIN_MARKET_ID, BUILTIN_PLACEHOLDER_VERSION)
        }
    }

    /** 兜底确保 default.yaml 存在（首次复制失败/旧结构迁移时可能缺失），缺失才补且不覆盖已有内容。 */
    private fun ensureDefaultYaml(context: Context, targetDir: File) {
        val defaultYaml = File(targetDir, "default.yaml")
        if (defaultYaml.exists()) return
        copyAssetFile(context, "$ASSETS_RIME_DIR/default.yaml", defaultYaml)
    }
    
    /**
     * 默认方案强制更新：递归遍历 assets 内置清单，对 .yaml/.lua 文件做内容比对，
     * 缺失或内容不同才覆盖。assets 清单之外的第三方文件不受影响。
     *
     * 覆盖 default.yaml 会重置 schema_list，由调用方
     * [SchemaManager.applyEnabledSchemasToDefaultYaml] 紧随其后恢复用户启用列表。
     *
     * @return 覆盖（含新增）的文件数。
     */
    private fun updateBuiltinAssets(context: Context, targetDir: File): Int {
        var updated = 0
        fun sync(assetSub: String, targetSub: File) {
            val assetPath = if (assetSub.isEmpty()) ASSETS_RIME_DIR else "$ASSETS_RIME_DIR/$assetSub"
            for (fileName in context.assets.list(assetPath) ?: return) {
                val childAsset = if (assetSub.isEmpty()) fileName else "$assetSub/$fileName"
                val childTarget = File(targetSub, fileName)
                val subFiles = context.assets.list("$ASSETS_RIME_DIR/$childAsset")
                if (!subFiles.isNullOrEmpty()) {
                    childTarget.mkdirs()
                    sync(childAsset, childTarget)
                } else if (fileName.endsWith(".yaml") || fileName.endsWith(".lua")) {
                    // *.custom.yaml 是补丁层文件（用户定制 + app 运行时写入：
                    // setEnabledSchemas 的启用列表、DoEnsureT9SchemaPatches 的
                    // T9 注入与个人词库 packs），覆盖会抹掉用户配置与第三方方案——
                    // 一律排除出默认覆盖范围
                    if (fileName.endsWith(".custom.yaml")) continue
                    if (!childTarget.exists() || !assetContentEquals(context, "$ASSETS_RIME_DIR/$childAsset", childTarget)) {
                        copyAssetFile(context, "$ASSETS_RIME_DIR/$childAsset", childTarget)
                        updated++
                    }
                }
            }
        }
        sync("", targetDir)
        return updated
    }

    /** assets 文件与本地文件内容逐块比对（流式，避免大词典整读进内存）。 */
    private fun assetContentEquals(context: Context, assetPath: String, target: File): Boolean {
        return try {
            context.assets.open(assetPath).use { assetStream ->
                java.io.FileInputStream(target).use { fileStream ->
                    val assetBuf = ByteArray(BUFFER_SIZE)
                    val fileBuf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val nAsset = assetStream.read(assetBuf)
                        val nFile = fileStream.read(fileBuf)
                        if (nAsset != nFile) return false
                        if (nAsset < 0) return true
                        for (i in 0 until nAsset) {
                            if (assetBuf[i] != fileBuf[i]) return false
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    true
                }
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun copyAssetsRecursively(context: Context, assetPath: String, targetDir: File): Boolean {
        val files = context.assets.list(assetPath)
        
        if (files.isNullOrEmpty()) {
            return false
        }
        
        var copiedAny = false
        
        for (fileName in files) {
            val fullAssetPath = "$assetPath/$fileName"
            val targetFile = File(targetDir, fileName)
            
            try {
                val subFiles = context.assets.list(fullAssetPath)
                if (!subFiles.isNullOrEmpty()) {
                    if (!targetFile.exists()) {
                        targetFile.mkdirs()
                    }
                    if (copyAssetsRecursively(context, fullAssetPath, targetFile)) {
                        copiedAny = true
                    }
                } else if (fileName.endsWith(".yaml") || fileName.endsWith(".lua")) {
                    val needsCopy = try {
                        if (targetFile.exists()) {
                            val fd = context.assets.openFd(fullAssetPath)
                            val sameSize = targetFile.length() == fd.length
                            fd.close()
                            !sameSize
                        } else true
                    } catch (_: Exception) {
                        true
                    }
                    if (needsCopy) {
                        copyAssetFile(context, fullAssetPath, targetFile)
                        copiedAny = true
                    }
                }
            } catch (e: IOException) {
                FileLogger.e(TAG, "Failed to process: $fullAssetPath", e)
            }
        }
        
        return copiedAny
    }
    
    private fun copyAssetFile(context: Context, assetPath: String, targetFile: File) {
        try {
            if (targetFile.exists() && targetFile.name.contains("custom")) {
                return
            }

            targetFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            FileLogger.e(TAG, "Failed to copy: $assetPath", e)
        }
    }

    private fun migrateOldStructure(context: Context, rimeDir: File) {
        val oldSharedDir = File(context.filesDir, "rime/shared")
        val oldUserDir = File(context.filesDir, "rime/user")
        
        if (!oldSharedDir.exists() && !oldUserDir.exists()) return
        
        Log.i(TAG, "Migrating old rime directory structure to single rime/ dir...")
        
        if (!rimeDir.exists()) rimeDir.mkdirs()
        
        // 迁移 user 数据（用户配置、build 产物、userdb）
        if (oldUserDir.exists()) {
            oldUserDir.listFiles()?.forEach { file ->
                val target = File(rimeDir, file.name)
                if (!target.exists()) {
                    file.renameTo(target)
                }
            }
        }
        
        // 迁移 shared 数据（方案文件）
        if (oldSharedDir.exists()) {
            oldSharedDir.listFiles()?.forEach { file ->
                val target = File(rimeDir, file.name)
                if (!target.exists()) {
                    file.renameTo(target)
                }
            }
        }
        
        // 删除旧目录
        oldSharedDir.deleteRecursively()
        oldUserDir.deleteRecursively()
        
        Log.i(TAG, "Migration complete")
    }

    /** 迁移旧版 market 目录（rime/market/ → market/）。 */
    private fun migrateOldMarketDir(context: Context) {
        val oldMarket = File(context.filesDir, "rime/market")
        if (!oldMarket.exists()) return

        val newMarket = SchemaManager.getMarketDir(context)
        if (!newMarket.exists()) {
            // 新位置不存在，直接重命名
            if (oldMarket.renameTo(newMarket)) {
                Log.i(TAG, "Migrated rime/market/ -> market/")
            } else {
                FileLogger.w(TAG, "Failed to rename rime/market/ to market/")
            }
        } else {
            // 新位置已存在，逐项合并
            oldMarket.listFiles()?.forEach { sub ->
                val target = File(newMarket, sub.name)
                if (!target.exists()) {
                    sub.renameTo(target)
                }
            }
            oldMarket.deleteRecursively()
            Log.i(TAG, "Merged rime/market/ into market/")
        }
    }

}