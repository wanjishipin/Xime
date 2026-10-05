package com.kingzcheung.xime.speech

import com.kingzcheung.xime.plugin.core.api.AsrPluginBackend
import com.kingzcheung.xime.plugin.core.api.AsrPluginListener
import com.kingzcheung.xime.plugin.core.api.AsrPluginState

class PluginAsrBackendAdapter(
    private val pluginName: String,
    private val pluginBackend: AsrPluginBackend
) : AsrBackend {

    override val name: String = pluginName

    private var resultCallback: ((String) -> Unit)? = null
    private var partialResultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    private val listener = object : AsrPluginListener {
        override fun onFinal(text: String) {
            resultCallback?.invoke(text)
        }

        override fun onPartial(text: String) {
            partialResultCallback?.invoke(text)
        }

        override fun onError(message: String) {
            errorCallback?.invoke(message)
        }

        override fun onStateChanged(state: AsrPluginState) {
            stateCallback?.invoke(state.toRecognitionState())
        }
    }

    override fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)?,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        resultCallback = onResult
        partialResultCallback = onPartialResult
        stateCallback = onStateChange
        errorCallback = onError
        pluginBackend.setListener(listener)
    }

    override fun initialize(): Boolean = pluginBackend.initialize()

    override fun start(): Boolean = pluginBackend.start()

    override fun processAudioChunk(buffer: ByteArray) {
        pluginBackend.processAudioChunk(buffer)
    }

    override fun stop() {
        pluginBackend.stop()
    }

    override fun cancel() {
        pluginBackend.cancel()
    }

    override fun release() {
        pluginBackend.release()
    }

    override fun isAvailable(): Boolean = true

    private fun AsrPluginState.toRecognitionState(): RecognitionState = when (this) {
        AsrPluginState.IDLE -> RecognitionState.IDLE
        AsrPluginState.LISTENING -> RecognitionState.LISTENING
        AsrPluginState.PROCESSING -> RecognitionState.PROCESSING
        AsrPluginState.ERROR -> RecognitionState.ERROR
    }
}
