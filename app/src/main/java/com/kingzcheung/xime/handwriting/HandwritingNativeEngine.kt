package com.kingzcheung.xime.handwriting

import android.content.Context
import android.util.Log
import java.io.File

object HandwritingNativeEngine {
    private const val TAG = "HandwritingNativeEngine"
    private var nativeLoaded = false

    fun loadNativeLibrary(context: Context): Boolean {
        val libsToLoad = listOf("libonnxruntime.so", "libhandwriting_jni.so")
        for (libName in libsToLoad) {
            if (!loadSingleLibrary(context, libName)) {
                Log.e(TAG, "Failed to load $libName")
                return false
            }
        }
        nativeLoaded = true
        Log.d(TAG, "All native libraries loaded successfully")
        return true
    }

    private fun loadSingleLibrary(context: Context, libName: String): Boolean {
        val simpleName = libName.removePrefix("lib").removeSuffix(".so")
        try {
            System.loadLibrary(simpleName)
            Log.d(TAG, "Loaded $libName via System.loadLibrary")
            return true
        } catch (e: UnsatisfiedLinkError) {
            if (e.message?.contains("already opened") == true || e.message?.contains("already loaded") == true) {
                Log.d(TAG, "$libName already loaded, skipping")
                return true
            }
            Log.d(TAG, "System.loadLibrary failed for $libName: ${e.message}")
            val nativeLibDir = context.applicationInfo?.nativeLibraryDir
            if (nativeLibDir != null) {
                val libFile = File(nativeLibDir, libName)
                if (libFile.exists()) {
                    try {
                        System.load(libFile.absolutePath)
                        Log.d(TAG, "Loaded $libName from nativeLibraryDir")
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
            Log.d(TAG, "Native method already available")
            return true
        } catch (e: UnsatisfiedLinkError) {
            Log.d(TAG, "Native method not available, loading libraries...")
        }
        if (!loadNativeLibrary(context)) {
            Log.e(TAG, "Native libraries not loaded")
            return false
        }
        return try {
            nativeInitialize(modelPath)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native method still unavailable: ${e.message}")
            nativeLoaded = false
            false
        } catch (e: Exception) {
            Log.e(TAG, "Native method failed: ${e.message}", e)
            false
        }
    }

    fun predict(strokeData: FloatArray, mask: ByteArray, topK: Int): Array<Pair<Int, Float>> {
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
        nativeRelease()
    }

    fun isInitialized(): Boolean {
        return nativeIsInitialized()
    }

    private external fun nativeInitialize(modelPath: String): Boolean
    private external fun nativePredict(strokeData: FloatArray, mask: ByteArray, topK: Int): Array<String>?
    private external fun nativeRelease()
    private external fun nativeIsInitialized(): Boolean
}
