package com.kingzcheung.xime.handwriting

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object WubiLookup {
    private var map: Map<String, String>? = null

    fun load(context: Context) {
        if (map != null) return
        val m = mutableMapOf<String, String>()
        try {
            val stream = context.assets.open("rime/wubi86.dict.yaml")
            BufferedReader(InputStreamReader(stream)).use { reader ->
                var inBody = false
                reader.forEachLine { line ->
                    if (!inBody) {
                        if (line == "...") { inBody = true }
                        return@forEachLine
                    }
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                    val parts = trimmed.split("\t", limit = 2)
                    if (parts.size == 2) {
                        val char = parts[0]
                        val code = parts[1]
                        // Keep the shortest code for each character
                        val existing = m[char]
                        if (existing == null || code.length < existing.length) {
                            m[char] = code
                        }
                    }
                }
            }
            map = m
        } catch (e: Exception) {
            map = emptyMap()
        }
    }

    fun lookup(char: String): String = map?.get(char) ?: ""
}
