package com.kingzcheung.xime.model

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ModelIndexLoader {

    private const val TAG = "ModelIndexLoader"
    private const val MODELS_INDEX_PATH = "models/index.yaml"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    suspend fun loadFromRemote(context: Context): List<ModelInfo> = withContext(Dispatchers.IO) {
        val cfg = KeysConfigHelper.loadXimeIndexConfig(context)
        val baseUrls = cfg.baseUrls

        FileLogger.i(TAG, "Loading model index from ${baseUrls.size} mirrors")

        for (base in baseUrls) {
            val host = base.substringAfter("://").substringBefore("/")
            try {
                val url = base.trimEnd('/') + "/" + MODELS_INDEX_PATH
                val text = fetchText(url)
                if (text != null) {
                    val result = parseModelsIndex(text)
                    if (result.isNotEmpty()) {
                        FileLogger.i(TAG, "Loaded ${result.size} models from $host")
                        return@withContext result
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch from $host: ${e.message}")
            }
        }

        FileLogger.w(TAG, "All mirrors failed for model index")
        emptyList()
    }

    internal fun parseModelsIndex(modelsText: String): List<ModelInfo> {
        return try {
            val root = yaml.parseToYamlNode(modelsText) as? YamlMap ?: return emptyList()
            val modelsNode = root["models"] as? YamlList ?: return emptyList()

            modelsNode.items.mapNotNull { node ->
                parseModelEntry(node as? YamlMap ?: return@mapNotNull null)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to parse models index: ${e.message}")
            emptyList()
        }
    }

    private fun parseModelEntry(entry: YamlMap): ModelInfo? {
        val id = (entry["id"] as? YamlScalar)?.content ?: return null
        val name = (entry["name"] as? YamlScalar)?.content ?: return null
        val description = (entry["description"] as? YamlScalar)?.content ?: ""
        val categoryStr = (entry["category"] as? YamlScalar)?.content ?: "other"
        val size = (entry["size"] as? YamlScalar)?.content ?: ""

        val category = parseCategory(categoryStr)

        val versionsNode = entry["versions"] as? YamlList ?: return null
        val latestVersion = versionsNode.items.firstOrNull() as? YamlMap ?: return null

        val storageDir = (latestVersion["storageDir"] as? YamlScalar)?.content ?: ""

        val archiveUrl = (latestVersion["archive"] as? YamlMap)
            ?.let { (it["url"] as? YamlScalar)?.content }

        val files = parseFiles(latestVersion)

        return ModelInfo(
            id = id,
            name = name,
            description = description,
            category = category,
            size = size,
            storageDir = storageDir,
            files = files,
            archiveUrl = archiveUrl
        )
    }

    private fun parseFiles(versionNode: YamlMap): List<ModelFile> {
        val filesNode = versionNode["files"] as? YamlList ?: return emptyList()
        return filesNode.items.mapNotNull { node ->
            val fileEntry = node as? YamlMap ?: return@mapNotNull null
            val name = (fileEntry["name"] as? YamlScalar)?.content ?: return@mapNotNull null
            val url = (fileEntry["url"] as? YamlScalar)?.content ?: ""
            ModelFile(name = name, downloadUrl = url)
        }
    }

    private fun parseCategory(category: String): ModelCategory {
        return when (category.lowercase()) {
            "prediction" -> ModelCategory.PREDICTION
            "asr" -> ModelCategory.ASR
            "punctuation" -> ModelCategory.PUNCTUATION
            "handwriting" -> ModelCategory.HANDWRITING
            else -> ModelCategory.OTHER
        }
    }

    private fun fetchText(url: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.takeIf { it.isNotBlank() }
            } else {
                Log.w(TAG, "HTTP ${response.code} fetching $url")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch $url: ${e.message}")
            null
        }
    }
}
