package com.kingzcheung.xime.speech.models

/**
 * 内置默认模型：sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30。
 * 字级词表，中文识别，无标点输出。
 */
val ZipformerZhInt8Profile = AsrModelProfile(
    id = "zipformer-zh-int8",
    name = "中文 Zipformer int8",
    description = "Zipformer 架构，适合实时语音识别，int8 量化",
    language = "zh",
    size = "132.63MB",
    downloadUrl = "https://www.modelscope.cn/models/bikeand/asr/resolve/master/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
    encoderFile = "encoder.int8.onnx",
    decoderFile = "decoder.onnx",
    joinerFile = "joiner.int8.onnx",
)
