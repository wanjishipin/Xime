package com.kingzcheung.xime.speech

interface AsrBackend {
    val name: String
    
    fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    )
    
    fun initialize(): Boolean
    fun start(): Boolean
    fun processAudioChunk(buffer: ByteArray)
    fun stop()
    fun cancel()
    fun release()
    fun isAvailable(): Boolean
}
