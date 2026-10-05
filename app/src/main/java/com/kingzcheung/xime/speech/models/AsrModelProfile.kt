package com.kingzcheung.xime.speech.models

/**
 * 单个本地 ASR 模型的适配描述（一个模型一个 profile）。
 *
 * 自研 streaming zipformer2 后端按文件角色加载模型，各模型的实际文件名
 * 由 profile 声明；索引（扩展商店 models/index.yaml）只提供下载地址与
 * 展示信息，文件角色映射一律以 profile 为准。
 *
 * 新增模型适配 = 新增一个 [AsrModelProfile] 实例并加入 [AsrModelRegistry]。
 */
data class AsrModelProfile(
    /** 模型 id，同时是 filesDir/models/<id>/ 的目录名与索引条目 id。 */
    val id: String,
    val name: String,
    val description: String,
    /** 支持的语言，如 "zh"、"zh-en"。 */
    val language: String,
    /** 展示用体积（与索引条目一致，按压缩包字节数的十进制 MB）。 */
    val size: String,
    /** 索引未收录时的兜底下载地址（tar.bz2，顶层目录解压时剥离）。 */
    val downloadUrl: String,
    val encoderFile: String,
    val decoderFile: String,
    val joinerFile: String,
    val tokensFile: String = "tokens.txt",
)
