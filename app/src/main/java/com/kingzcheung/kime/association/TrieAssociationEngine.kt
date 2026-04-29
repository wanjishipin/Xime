package com.kingzcheung.kime.association

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TrieNodeData(
    val children: ByteArray = ByteArray(0),
    val childIndices: IntArray = IntArray(0),
    val word: String? = null,
    val frequency: Int = 0
)

class TrieAssociationEngine {
    private var nodes: Array<TrieNodeData>? = null
    private var isInitialized = false
    
    companion object {
        private const val TAG = "TrieAssociationEngine"
        private const val BINARY_FILE = "english_trie.bin"
        private const val TEXT_FILE = "english.txt"
        private var instance: TrieAssociationEngine? = null
        
        fun getInstance(): TrieAssociationEngine {
            if (instance == null) {
                instance = TrieAssociationEngine()
            }
            return instance!!
        }
    }
    
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return@withContext true
        }
        
        try {
            val startTime = System.currentTimeMillis()
            
            val binaryExists = try {
                context.assets.list("")?.contains(BINARY_FILE) ?: false
            } catch (e: Exception) {
                false
            }
            
            if (binaryExists) {
                loadBinaryTrie(context)
            } else {
                Log.w(TAG, "Binary trie not found, building from text")
                buildTrieFromText(context)
            }
            
            isInitialized = true
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Trie engine initialized in $elapsed ms (${nodes?.size ?: 0} nodes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize trie engine", e)
            false
        }
    }
    
    private fun loadBinaryTrie(context: Context) {
        val bytes = context.assets.open(BINARY_FILE).readBytes()
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        val magic = ByteArray(4)
        buffer.get(magic)
        require(magic.contentEquals("TRIE".toByteArray())) { "Invalid trie file" }
        
        val version = buffer.get()
        require(version.toInt() == 1) { "Unsupported trie version: $version" }
        
        val nodeCount = buffer.getInt()
        nodes = Array(nodeCount) { TrieNodeData() }
        
        for (i in 0 until nodeCount) {
            val childCount = buffer.get().toInt()
            val children = ByteArray(childCount)
            val childIndices = IntArray(childCount)
            
            for (j in 0 until childCount) {
                children[j] = buffer.get()
                childIndices[j] = buffer.getInt()
            }
            
            val hasWord = buffer.get().toInt()
            var word: String? = null
            var freq = 0
            
            if (hasWord == 1) {
                val wordLen = buffer.get().toInt()
                val wordBytes = ByteArray(wordLen)
                buffer.get(wordBytes)
                word = String(wordBytes, Charsets.UTF_8)
                freq = buffer.getInt()
            }
            
            nodes!![i] = TrieNodeData(children, childIndices, word, freq)
        }
        
        Log.d(TAG, "Loaded binary trie: $nodeCount nodes, ${bytes.size} bytes")
    }
    
    private fun buildTrieFromText(context: Context) {
        val tempChildren = mutableListOf<ByteArray>()
        val tempIndices = mutableListOf<IntArray>()
        val tempWords = mutableListOf<String?>()
        val tempFreqs = mutableListOf<Int>()
        
        tempChildren.add(ByteArray(0))
        tempIndices.add(IntArray(0))
        tempWords.add(null)
        tempFreqs.add(0)
        
        fun getOrCreateChild(parentIdx: Int, char: Char): Int {
            val pChildren = tempChildren[parentIdx]
            val pIndices = tempIndices[parentIdx]
            val charByte = char.code.toByte()
            
            for (i in pChildren.indices) {
                if (pChildren[i] == charByte.toByte()) return pIndices[i]
            }
            
            val newIdx = tempChildren.size
            tempChildren.add(ByteArray(0))
            tempIndices.add(IntArray(0))
            tempWords.add(null)
            tempFreqs.add(0)
            
            val newChildren = ByteArray(pChildren.size + 1)
            val newIndices = IntArray(pIndices.size + 1)
            pChildren.copyInto(newChildren)
            pIndices.copyInto(newIndices)
            newChildren[pChildren.size] = charByte
            newIndices[pIndices.size] = newIdx
            
            tempChildren[parentIdx] = newChildren
            tempIndices[parentIdx] = newIndices
            return newIdx
        }
        
        context.assets.open(TEXT_FILE).bufferedReader().use { reader ->
            var lineNum = 0
            reader.lineSequence().forEach { line ->
                lineNum++
                val word = line.trim().lowercase()
                if (word.isNotEmpty()) {
                    var current = 0
                    for (char in word) {
                        current = getOrCreateChild(current, char)
                    }
                    if (tempWords[current] == null) {
                        tempWords[current] = word
                        tempFreqs[current] = lineNum
                    }
                }
            }
        }
        
        nodes = Array(tempChildren.size) { i ->
            TrieNodeData(tempChildren[i], tempIndices[i], tempWords[i], tempFreqs[i])
        }
        
        Log.d(TAG, "Built trie from text: ${nodes!!.size} nodes")
    }
    
    suspend fun predict(prefix: String, topK: Int = 5): List<AssociationCandidate> = withContext(Dispatchers.Default) {
        if (!isInitialized || prefix.isEmpty() || nodes == null) {
            return@withContext emptyList()
        }
        
        val normalizedPrefix = prefix.lowercase()
        val startIdx = findNode(normalizedPrefix)
        
        if (startIdx < 0) return@withContext emptyList()
        
        val candidates = mutableListOf<AssociationCandidate>()
        collectWords(startIdx, candidates)
        
        candidates.sortedBy { it.score }.take(topK)
    }
    
    private fun findNode(prefix: String): Int {
        var current = 0
        for (char in prefix) {
            val node = nodes!![current]
            val charByte = char.code.toByte()
            var found = -1
            for (i in node.children.indices) {
                if (node.children[i] == charByte.toByte()) {
                    found = node.childIndices[i]
                    break
                }
            }
            if (found < 0) return -1
            current = found
        }
        return current
    }
    
    private fun collectWords(nodeIdx: Int, candidates: MutableList<AssociationCandidate>) {
        val node = nodes!![nodeIdx]
        
        if (node.word != null) {
            candidates.add(AssociationCandidate(node.word!!, node.frequency.toFloat()))
        }
        
        for (childIdx in node.childIndices) {
            collectWords(childIdx, candidates)
        }
    }
    
    fun isInitialized(): Boolean = isInitialized
    
    fun release() {
        nodes = null
        isInitialized = false
        Log.d(TAG, "Trie engine released")
    }
}