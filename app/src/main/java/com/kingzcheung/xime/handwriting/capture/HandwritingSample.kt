package com.kingzcheung.xime.handwriting.capture

/**
 * 单个手写采集样本：一个目标字 + 原始笔迹。
 *
 * 坐标语义与键盘手写（HandwritingKeyboardLayout）一致：
 * - x/y 为作画区局部像素坐标（左上角原点，y 向下），未做任何归一化；
 * - 时间为相对本次样本首笔起点的毫秒数（首笔起点 = 0）。
 * 归一化由训练侧复刻推理侧 HandwritingInference.strokesToSequence 的
 * bounding-box 算法完成，采集端只存原材料。
 */
data class HandwritingSample(
    val target: String,
    val canvasWidthPx: Float,
    val canvasHeightPx: Float,
    val strokes: List<List<StrokePointMs>>,
    /** 采集时的识别首选（用于离线对比模型效果），无模型/未识别时为 null */
    val modelTop: String? = null,
    /** 识别首选得分，无识别时为 null */
    val modelTopScore: Float? = null,
)

/** 带相对时间的采样点（ms 相对本样本首笔起点）。 */
data class StrokePointMs(
    val x: Float,
    val y: Float,
    val t: Long,
)
