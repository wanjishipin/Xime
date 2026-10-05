package com.kingzcheung.xime.service;

import com.kingzcheung.xime.service.IInferenceAsrCallback;

interface IInferenceAsrService {
    /** 加载 ASR 模型并开始识别（返回 false 表示模型缺失/加载失败） */
    boolean startAsr(String modelDir, IInferenceAsrCallback callback);
    /** 推送 PCM 音频数据（16k/16bit/mono），服务端内部流式识别 */
    void pushAsrAudio(in byte[] audioData);
    /** 结束识别，返回最终识别文本 */
    String stopAsr();
    /** 取消当前识别会话 */
    void cancelAsr();
    /** 释放 ASR 模型 */
    void releaseAsr();
    /**
     * "保持引擎常驻"开关：true 时服务端不做 60s 空闲自动释放，
     * 模型常驻内存（用户已明确选择以内存换响应速度）；false 恢复默认回收。
     */
    oneway void setKeepModelAlive(boolean keepAlive);
}
