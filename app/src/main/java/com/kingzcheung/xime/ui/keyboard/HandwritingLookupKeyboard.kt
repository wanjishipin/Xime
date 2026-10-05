package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.handwriting.HandwritingCandidate
import com.kingzcheung.xime.handwriting.HandwritingEngine
import com.kingzcheung.xime.handwriting.HandwritingStrokeFx
import com.kingzcheung.xime.handwriting.OverlappedHandwritingRecognizer
import com.kingzcheung.xime.handwriting.StrokePoint
import com.kingzcheung.xime.handwriting.renderStrokes
import com.kingzcheung.xime.keyboard.KeyboardDimensions
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HandwritingLookupKeyboard(
    keyTextColor: Color,
    specialKeyBgColor: Color,
    specialKeyTextColor: Color,
    keyboardBgColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: androidx.compose.ui.unit.Dp,
    shadowShapeRadius: androidx.compose.ui.unit.Dp,
    uiState: KeyboardUiState,
    onKeyPress: (String) -> Unit,
    onButtonFeedback: ((String) -> Unit)?,
    onCandidates: ((List<HandwritingCandidate>) -> Unit)?,
    onExit: () -> Unit,
    clearSignal: Int,
    modifier: Modifier = Modifier,
) {
    val strokes = remember { mutableStateListOf<List<StrokePoint>>() }
    // 与主手写键盘一致的叠写视觉状态（三段前缀渲染 + 识别窗口），见 HandwritingStrokeFx
    var settledCount by remember { mutableIntStateOf(0) }
    var fadingPrefix by remember { mutableIntStateOf(0) }
    var gonePrefix by remember { mutableIntStateOf(0) }
    var currentStrokePoints by remember { mutableStateOf<List<StrokePoint>>(emptyList()) }
    var dragVersion by remember { mutableIntStateOf(0) }
    var lastStrokeEndMs by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recognizer = remember { OverlappedHandwritingRecognizer() }
    var recognizeJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { HandwritingEngine.initialize(context) } }

    /** 视觉消失调度：450ms 后 gonePrefix 推进到 target（笔画数据保留，渲染层跳过）。 */
    fun scheduleGone(target: Int) {
        scope.launch {
            delay(450L)
            if (gonePrefix < target) {
                gonePrefix = target.coerceAtMost(fadingPrefix)
                dragVersion++
            }
        }
    }

    // 停顿分割（自适应阈值，同主手写键盘）：定型=笔画变淡后消失、识别窗口清空。
    // 查词无自动上屏，候选栏保留供点选，直到下一次书写
    LaunchedEffect(lastStrokeEndMs) {
        if (lastStrokeEndMs > 0L) {
            delay(HandwritingStrokeFx.splitPauseMs(strokes.size - settledCount))
            if (strokes.isNotEmpty()) {
                fadingPrefix = strokes.size
                settledCount = strokes.size
                dragVersion++
                recognizer.reset()
                scheduleGone(strokes.size)
            }
        }
    }
    LaunchedEffect(clearSignal) {
        strokes.clear()
        fadingPrefix = 0
        gonePrefix = 0
        settledCount = 0
        dragVersion++
        recognizer.reset()
    }

    /**
     * 叠写识别调度（与主手写键盘同源）：DP 切分 + 笔间间隔时间偏置。
     * 查词上报最后一段候选（正在写的字）；前面段（已完成字，需存在换字停顿）
     * 笔画变淡——连笔中途切分抖动不淡出。
     */
    fun scheduleRecognition() {
        recognizeJob?.cancel()
        if (settledCount >= strokes.size) return
        recognizeJob = scope.launch {
            val window = strokes.drop(settledCount)
            val pairs = window.map { stroke -> stroke.map { Pair(it.x, it.y) } }
            val gaps = HandwritingStrokeFx.windowGaps(window)
            val result = withContext(Dispatchers.Default) {
                recognizer.recognize(pairs, gaps)
            }
            if (!isActive) return@launch
            if (result.segments.isNotEmpty()) {
                onCandidates?.invoke(result.segments.last().candidates)
                if (result.segments.size >= 2) {
                    val doneStrokes = HandwritingStrokeFx.settledStrokesBeforeCurrent(result.segments, gaps)
                    if (doneStrokes > 0) {
                        val target = (settledCount + doneStrokes).coerceAtLeast(fadingPrefix)
                        if (target > fadingPrefix) {
                            fadingPrefix = target
                            dragVersion++
                            scheduleGone(target)
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(
                bottom = if (uiState.isFloatingMode) {
                    0.dp
                } else {
                    10.dp
                }
            )
    ) {
        Box(Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    lastStrokeEndMs = 0L

                    var dragged = false
                    do {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull() ?: break

                        if (ch.pressed) {
                            ch.consume()
                            if (!dragged) {
                                val dist = (ch.position - down.position).getDistance()
                                if (dist > 12f) {
                                    dragged = true
                                    currentStrokePoints =
                                        listOf(StrokePoint(down.position.x, down.position.y))
                                    dragVersion++
                                }
                            } else {
                                currentStrokePoints =
                                    currentStrokePoints + StrokePoint(ch.position.x, ch.position.y)
                                dragVersion++
                            }
                        } else {
                            if (dragged) {
                                val finalStroke = currentStrokePoints
                                if (finalStroke.size >= 2) {
                                    strokes.add(finalStroke)
                                    currentStrokePoints = emptyList()
                                }
                                currentStrokePoints = emptyList()
                                lastStrokeEndMs = System.currentTimeMillis()
                                if (strokes.isNotEmpty()) scheduleRecognition()
                            }
                            break
                        }
                    } while (true)
                }
            })

        key(dragVersion) {
            Canvas(Modifier.fillMaxSize()) {
                // 三段渲染（同主手写键盘）：消失段不画、变淡段 0.3 透明度。
                // 正在书写的笔画必须无条件渲染（尚未进入 strokes）
                if (gonePrefix < fadingPrefix && gonePrefix < strokes.size) {
                    renderStrokes(
                        strokes.drop(gonePrefix).take(fadingPrefix - gonePrefix),
                        emptyList(),
                        keyTextColor.copy(alpha = 0.3f),
                    )
                }
                renderStrokes(strokes.drop(fadingPrefix), currentStrokePoints, keyTextColor)
            }
        }

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(3f).fillMaxWidth())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
            ) {
                KeyButton("返回", { onExit() }, specialKeyBgColor, specialKeyTextColor, Modifier.weight(1f), onPress = { onButtonFeedback?.invoke("exit") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                Spacer(Modifier.weight(4f))
                KeyButton("回车", { onKeyPress("enter") }, specialKeyBgColor, specialKeyTextColor, Modifier.weight(1f), onPress = { onButtonFeedback?.invoke("enter") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
            }
        }
    }
}
