package com.kingzcheung.xime.handwriting

import android.content.Context
import android.util.Log
import java.io.File

object HandwritingNativeEngine {
    private const val TAG = "HandwritingNativeEngine"

    /**
     * native 生命周期 Java 侧镜像。libhandwriting_jni.so 懒加载（首次 initialize
     * 才装载），此前任何裸调 nativeIsInitialized/nativeRelease 都会抛
     * UnsatisfiedLinkError 炸掉 binder 线程（:inference 进程启动后首次
     * loadModel/isModelLoaded/unloadModel 即触发）。状态由 initialize/release
     * 维护，库未加载时各查询安全返回 false。
     */
    @Volatile
    private var nativeReady = false

    fun loadNativeLibrary(context: Context): Boolean {
        val libsToLoad = listOf("libonnxruntime.so", "libhandwriting_jni.so")
        for (libName in libsToLoad) {
            if (!loadSingleLibrary(context, libName)) {
                Log.e(TAG, "Failed to load $libName")
                return false
            }
        }
        return true
    }

    private fun loadSingleLibrary(context: Context, libName: String): Boolean {
        val simpleName = libName.removePrefix("lib").removeSuffix(".so")
        try {
            System.loadLibrary(simpleName)
            return true
        } catch (e: UnsatisfiedLinkError) {
            if (e.message?.contains("already opened") == true || e.message?.contains("already loaded") == true) {
                return true
            }
            val nativeLibDir = context.applicationInfo?.nativeLibraryDir
            if (nativeLibDir != null) {
                val libFile = File(nativeLibDir, libName)
                if (libFile.exists()) {
                    try {
                        System.load(libFile.absolutePath)
                        return true
                    } catch (e2: UnsatisfiedLinkError) {
                        if (e2.message?.contains("already opened") == true || e2.message?.contains("already loaded") == true) {
                            return true
                        }
                        Log.e(TAG, "Failed to load from nativeLibraryDir: ${e2.message}")
                    }
                }
            }
            return false
        }
    }

    fun initialize(context: Context, modelPath: String): Boolean {
        try {
            nativeInitialize(modelPath)
            nativeReady = true
            return true
        } catch (e: UnsatisfiedLinkError) {
        }
        if (!loadNativeLibrary(context)) {
            Log.e(TAG, "Native libraries not loaded")
            return false
        }
        return try {
            nativeReady = nativeInitialize(modelPath)
            nativeReady
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Native method still unavailable: ${e.message}")
        nativeReady = false
        false
        } catch (e: Exception) {
            Log.e(TAG, "Native method failed: ${e.message}", e)
            nativeReady = false
            false
        }
    }

    fun predict(strokeData: FloatArray, mask: ByteArray, topK: Int): Array<Pair<Int, Float>> {
        if (!nativeReady) return emptyArray()
        val result = nativePredict(strokeData, mask, topK) ?: return emptyArray()
        val pairs = mutableListOf<Pair<Int, Float>>()
        for (i in result.indices step 2) {
            val idx = result[i].toIntOrNull() ?: continue
            val score = result[i + 1].toFloatOrNull() ?: continue
            pairs.add(Pair(idx, score))
        }
        return pairs.toTypedArray()
    }

    fun release() {
        if (nativeReady) {
            nativeRelease()
            nativeReady = false
        }
    }

    fun isInitialized(): Boolean {
        return nativeReady
    }

    private external fun nativeInitialize(modelPath: String): Boolean
    private external fun nativePredict(strokeData: FloatArray, mask: ByteArray, topK: Int): Array<String>?
    private external fun nativeRelease()
    private external fun nativeIsInitialized(): Boolean
}
