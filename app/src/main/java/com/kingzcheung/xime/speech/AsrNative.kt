package com.kingzcheung.xime.speech

/**
 * JNI bridge to the self-implemented streaming zipformer2 ASR
 * (libasr_jni.so). Reference algorithm: sherpa-onnx (Apache-2.0);
 * feature extraction: kaldi-native-fbank (Apache-2.0).
 */
object AsrNative {
    init {
        System.loadLibrary("asr_jni")
    }

    external fun nativeCreate(
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String
    ): Long

    external fun nativeReset(handle: Long)

    external fun nativeAcceptPcm(handle: Long, pcm: ByteArray)

    external fun nativeGetPartial(handle: Long): String

    external fun nativeFinalize(handle: Long): String

    external fun nativeRelease(handle: Long)
}
