package com.kingzcheung.xime.service;

interface IInferenceService {
    /** 加载模型 */
    boolean loadModel(String modelId, String modelPath, String extraPath);
    void unloadModel(String modelId);
    boolean isModelLoaded(String modelId);

    /** 智能联想预测 — 返回交替 [word, score, word, score, ...] */
    List<String> predict(String modelId, String text, int topK);

    /** 手写识别 — 传入原始笔画（points = 扁平 [x0,y0,x1,y1,...]，strokePointCounts = 每笔画点数），
        服务端完成预处理与汉字映射，返回交替 [字, score, 字, score, ...] */
    List<String> recognizeHandwriting(String modelId, in float[] points, in int[] strokePointCounts, int topK);

    /** 语音前处理（AGC 等） */
    byte[] processAudioBytes(in byte[] input, int sampleRate);
}
