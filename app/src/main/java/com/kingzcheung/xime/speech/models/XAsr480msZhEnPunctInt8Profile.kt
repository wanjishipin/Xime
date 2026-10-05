package com.kingzcheung.xime.speech.models

/**
 * X-ASR-zh-en 流式模型（480ms chunk，int8 量化，带标点变体）。
 *
 * 基于 icefall/Zipformer transducer，约 100 万小时中英文数据训练，
 * 5000 BPE 词表（后端已支持 ▁ 剥离与 <0xNN> 字节回退）。
 * 词表含标点 token，输出带中英文标点；英文输出大小写混合。
 */
val XAsr480msZhEnPunctInt8Profile = AsrModelProfile(
    id = "x-asr-480ms-zh-en-punct-int8",
    name = "X-ASR 中英文语音识别（带标点）",
    description = "中英文混合识别，说中文、说英文都行，结果自动加标点，边说边出字，离线运行",
    language = "zh-en",
    size = "133.90MB",
    downloadUrl = "https://www.modelscope.cn/models/adaada88/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8/resolve/master/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2",
    encoderFile = "encoder.int8.onnx",
    decoderFile = "decoder.onnx",
    joinerFile = "joiner.int8.onnx",
)
