package com.kingzcheung.xime.rime

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.settings.SchemaConfigHelper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object RimeConfigHelper {
    private const val TAG = "RimeConfigHelper"
    private const val ASSETS_RIME_DIR = "rime"
    
    suspend fun initializeRimeDataAsync(context: Context): Pair<String, String> {
        val sharedDataDir = File(context.filesDir, "rime/shared")
        val userDataDir = File(context.filesDir, "rime/user")
        
        Log.d(TAG, "initializeRimeData: sharedDataDir=${sharedDataDir.absolutePath}")
        Log.d(TAG, "initializeRimeData: userDataDir=${userDataDir.absolutePath}")
        
        if (!sharedDataDir.exists()) {
            sharedDataDir.mkdirs()
        }
        if (!userDataDir.exists()) {
            userDataDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, sharedDataDir)
        
        Log.d(TAG, "Checking for missing schema files...")
        val downloaded = SchemaConfigHelper.downloadMissingSchemas(context)
        if (downloaded.isNotEmpty()) {
            Log.i(TAG, "Downloaded schemas: $downloaded")
        }
        
        checkAndCleanBuildDir(sharedDataDir, userDataDir)
        listFilesRecursively(sharedDataDir, TAG)
        
        return Pair(userDataDir.absolutePath, sharedDataDir.absolutePath)
    }
    
    fun initializeRimeData(context: Context): Pair<String, String> {
        val sharedDataDir = File(context.filesDir, "rime/shared")
        val userDataDir = File(context.filesDir, "rime/user")
        
        Log.d(TAG, "initializeRimeData: sharedDataDir=${sharedDataDir.absolutePath}")
        Log.d(TAG, "initializeRimeData: userDataDir=${userDataDir.absolutePath}")
        
        if (!sharedDataDir.exists()) {
            sharedDataDir.mkdirs()
        }
        if (!userDataDir.exists()) {
            userDataDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, sharedDataDir)
        checkAndCleanBuildDir(sharedDataDir, userDataDir)
        listFilesRecursively(sharedDataDir, TAG)
        
        return Pair(userDataDir.absolutePath, sharedDataDir.absolutePath)
    }
    
    private fun checkAndCleanBuildDir(sharedDataDir: File, userDataDir: File) {
        val buildDir = File(userDataDir, "build")
        val schemasNeedingDeploy = mutableListOf<String>()
        
        val defaultYaml = File(sharedDataDir, "default.yaml")
        if (defaultYaml.exists()) {
            try {
                val content = defaultYaml.readText()
                val schemaListRegex = Regex("""schema:\s*(\S+)""")
                val schemas = schemaListRegex.findAll(content).map { it.groupValues[1] }.toList()
                Log.d(TAG, "Schemas in default.yaml: $schemas")
                
                for (schema in schemas) {
                    val prismFile = File(buildDir, "$schema.prism.bin")
                    if (!prismFile.exists()) {
                        schemasNeedingDeploy.add(schema)
                        Log.d(TAG, "Schema $schema needs deployment (missing ${prismFile.name})")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse default.yaml", e)
            }
        }
        
        if (schemasNeedingDeploy.isNotEmpty() && buildDir.exists()) {
            Log.d(TAG, "Schemas needing deploy: $schemasNeedingDeploy, cleaning build directory")
            buildDir.deleteRecursively()
        }
    }
    
    private fun copyAssetsToRimeDir(context: Context, targetDir: File): Boolean {
        try {
            return copyAssetsRecursively(context, ASSETS_RIME_DIR, targetDir)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy assets", e)
            return false
        }
    }
    
    private fun copyAssetsRecursively(context: Context, assetPath: String, targetDir: File): Boolean {
        val files = context.assets.list(assetPath)
        
        if (files.isNullOrEmpty()) {
            Log.d(TAG, "No files found in assets/$assetPath")
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
                    Log.d(TAG, "Processing subdirectory: $fullAssetPath")
                    if (copyAssetsRecursively(context, fullAssetPath, targetFile)) {
                        copiedAny = true
                    }
                } else if (fileName.endsWith(".yaml")) {
                    copyAssetFile(context, fullAssetPath, targetFile)
                    copiedAny = true
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to process: $fullAssetPath", e)
            }
        }
        
        return copiedAny
    }
    
    private fun copyAssetFile(context: Context, assetPath: String, targetFile: File) {
        try {
            if (targetFile.exists()) {
                Log.d(TAG, "Overwriting existing file: ${targetFile.name}")
                targetFile.delete()
            }
            
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Copied: $assetPath -> ${targetFile.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy: $assetPath", e)
        }
    }
    
    private fun listFilesRecursively(dir: File, tag: String, prefix: String = "") {
        val files = dir.listFiles()
        if (files == null) {
            Log.e(tag, "$prefix${dir.name} is empty or not a directory!")
            return
        }
        Log.d(tag, "$prefix${dir.name}/ (${files.size} items)")
        for (file in files) {
            if (file.isDirectory) {
                listFilesRecursively(file, tag, "$prefix  ")
            } else {
                Log.d(tag, "$prefix  ${file.name} (${file.length()} bytes)")
            }
        }
    }
}