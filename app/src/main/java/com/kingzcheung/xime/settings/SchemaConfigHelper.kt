package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object SchemaConfigHelper {
    private const val TAG = "SchemaConfigHelper"
    private const val RIME_ASSETS_DIR = "rime"
    
    data class BuiltInSchema(
        val schemaId: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val needsDict: Boolean = true,
        val downloadUrl: String? = null
    )
    
    private val builtInSchemas = listOf(
        BuiltInSchema(
            schemaId = "wubi86",
            name = "五笔86",
            version = "1.0",
            author = "王永民",
            description = "五笔字形86版",
            downloadUrl = "https://s.ximei.me/rime-wubi"
        ),
        BuiltInSchema(
            schemaId = "wubi86_pinyin",
            name = "五笔拼音",
            version = "1.0",
            author = "王永民",
            description = "五笔86版带拼音反查",
            needsDict = false,
            downloadUrl = "https://s.ximei.me/rime-wubi"
        ),
        BuiltInSchema(
            schemaId = "pinyin_simp",
            name = "简体拼音",
            version = "1.0",
            author = "Rime",
            description = "简体拼音输入",
            downloadUrl = "https://s.ximei.me/rime-wubi"
        ),
        BuiltInSchema(
            schemaId = "wubi98",
            name = "五笔98",
            version = "24.05.29",
            author = "王永民",
            description = "五笔字形98版",
            downloadUrl = "https://s.ximei.me/rime-wubi"
        ),
        BuiltInSchema(
            schemaId = "t9_pinyin",
            name = "拼音九键",
            version = "1.0",
            author = "Rime",
            description = "拼音九键输入（与简体拼音共享字典）",
            downloadUrl = null
        )
    )
    
    fun getBuiltInSchemas(): List<BuiltInSchema> = builtInSchemas
    
    fun getSchemaById(schemaId: String): BuiltInSchema? {
        return builtInSchemas.find { it.schemaId == schemaId }
    }
    
    fun loadSchemas(context: Context): List<SchemaInfo> {
        val schemaListIds = parseSchemaListFromDefault(context)
        Log.d(TAG, "Schema list from default.yaml: $schemaListIds")
        
        return schemaListIds.mapNotNull { schemaId ->
            val builtIn = getSchemaById(schemaId)
            if (builtIn != null) {
                val layouts = SchemaLayoutHelper.getSupportedLayouts(context, schemaId)
                SchemaInfo(
                    schemaId = builtIn.schemaId,
                    name = builtIn.name,
                    version = builtIn.version,
                    author = builtIn.author,
                    description = builtIn.description,
                    supportedLayouts = layouts
                )
            } else {
                Log.w(TAG, "Unknown schema in default.yaml: $schemaId")
                null
            }
        }
    }
    
    fun parseSchemaListFromDefault(context: Context): List<String> {
        val schemas = mutableListOf<String>()
        
        try {
            val inputStream = context.assets.open("$RIME_ASSETS_DIR/default.yaml")
            val content = inputStream.bufferedReader().readText()
            inputStream.close()
            
            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("- schema:")) {
                    val schemaId = trimmed.removePrefix("- schema:").trim()
                    if (schemaId.isNotEmpty()) {
                        schemas.add(schemaId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse default.yaml", e)
        }
        
        return schemas
    }
    
    fun checkSchemaFilesExist(context: Context, schemaId: String): Pair<Boolean, Boolean> {
        val sharedDir = File(context.filesDir, "rime/shared")
        val schemaFile = File(sharedDir, "$schemaId.schema.yaml")
        val dictFile = File(sharedDir, "$schemaId.dict.yaml")
        
        val builtIn = getSchemaById(schemaId)
        val needsDict = builtIn?.needsDict ?: true
        
        return Pair(schemaFile.exists(), if (needsDict) dictFile.exists() else true)
    }
    
    fun needsDownload(context: Context, schemaId: String): Boolean {
        val (schemaExists, dictExists) = checkSchemaFilesExist(context, schemaId)
        if (schemaExists && dictExists) return false

        // shared 目录没有但 assets 中存在的方案（尚未 sync 到 shared），视为可用
        try {
            context.assets.open("$RIME_ASSETS_DIR/$schemaId.schema.yaml").close()
            return false
        } catch (_: Exception) {
            // assets 中也不存在，确实需要下载
        }

        return true
    }
    
    suspend fun downloadSchema(context: Context, schemaId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val sharedDir = File(context.filesDir, "rime/shared")
                if (!sharedDir.exists()) {
                    sharedDir.mkdirs()
                }
                
                val builtIn = getSchemaById(schemaId)
                val downloadUrl = builtIn?.downloadUrl
                
                if (downloadUrl == null) {
                    Log.w(TAG, "Schema $schemaId has no download URL configured")
                    false
                } else {
                    Log.i(TAG, "Downloading schema: $schemaId from $downloadUrl")
                    
                    val schemaUrl = "$downloadUrl/$schemaId.schema.yaml"
                    val schemaFile = File(sharedDir, "$schemaId.schema.yaml")
                    downloadFile(schemaUrl, schemaFile)
                    Log.d(TAG, "Downloaded schema: $schemaFile.absolutePath")
                    
                    if (builtIn?.needsDict == true) {
                        val dictUrl = "$downloadUrl/$schemaId.dict.yaml"
                        val dictFile = File(sharedDir, "$schemaId.dict.yaml")
                        downloadFile(dictUrl, dictFile)
                        Log.d(TAG, "Downloaded dict: $dictFile.absolutePath")
                    }
                    
                    Log.i(TAG, "Schema $schemaId downloaded successfully")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download schema $schemaId", e)
                false
            }
        }
    }
    
    private fun downloadFile(url: String, targetFile: File) {
        val connection = URL(url).openConnection()
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        
        connection.getInputStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }
    
    suspend fun downloadMissingSchemas(context: Context): List<String> {
        val downloaded = mutableListOf<String>()
        val schemaListIds = parseSchemaListFromDefault(context)
        
        for (schemaId in schemaListIds) {
            if (needsDownload(context, schemaId)) {
                Log.d(TAG, "Schema $schemaId needs download")
                if (downloadSchema(context, schemaId)) {
                    downloaded.add(schemaId)
                }
            }
        }
        
        return downloaded
    }
}