package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.handwriting.HandwritingCandidate
import com.kingzcheung.xime.handwriting.HandwritingEngine
import com.kingzcheung.xime.handwriting.StrokePoint
import com.kingzcheung.xime.handwriting.renderStrokes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HandwritingKeyboardLayout(
    onKeyPress: (String) -> Unit = {},
    onCandidates: ((List<HandwritingCandidate>) -> Unit)? = null,
    onButtonFeedback: ((String) -> Unit)? = null,
    keyTextColor: Color = Color(0xFF333333),
    keyBackgroundColor: Color = Color(0xFFE0E0E0),
    specialKeyBackgroundColor: Color = Color(0xFFD0D0D0),
    bottomPaddingDp: Int = 18,
    modifier: Modifier = Modifier,
    clearSignal: Int = 0,
) {
    val strokes = remember { mutableStateListOf<List<StrokePoint>>() }
    var currentStrokePoints by remember { mutableStateOf<List<StrokePoint>>(emptyList()) }
    var dragVersion by remember { mutableIntStateOf(0) }
    var lastStrokeEndMs by remember { mutableLongStateOf(0L) }
    var pressedButton by remember { mutableIntStateOf(-1) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sheetWPx = with(density) { 56.dp.toPx() }
    val barHPx = with(density) { 48.dp.toPx() }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            HandwritingEngine.initialize(context)
        }
    }

    LaunchedEffect(lastStrokeEndMs) {
        if (lastStrokeEndMs > 0L) {
            delay(1000L)
            strokes.clear()
            dragVersion++
        }
    }

    suspend fun runPrediction() {
        if (!HandwritingEngine.isInitialized()) return
        val snapshot = strokes.toList()
        if (snapshot.isEmpty()) return
        val pairs = snapshot.map { stroke -> stroke.map { Pair(it.x, it.y) } }
        val result = withContext(Dispatchers.Default) {
            HandwritingEngine.predict(pairs, 20)
        }
        onCandidates?.invoke(result)
    }

    fun onStrokeEnd() {
        lastStrokeEndMs = System.currentTimeMillis()
    }

    Box(modifier = modifier.fillMaxSize().padding(bottom = bottomPaddingDp.dp)) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(56.dp)
                .padding(end = 4.dp, top = 4.dp, bottom = 52.dp),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf("，", "。", "？", "！", "删除").forEachIndexed { i, text ->
                val pressed = pressedButton == i
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(1.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (pressed) Color(0x80000000) else Color(0x12000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text, color = keyTextColor, fontSize = 16.sp,
                        fontWeight = if (text.length <= 1) FontWeight.Normal else FontWeight.Medium,
                        textAlign = TextAlign.Center)
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(48.dp)
                .padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("符号" to "symbol", "123" to "number", "空格" to "space",
                  "ABC" to "ime_switch", "换行" to "enter"
            ).forEachIndexed { i, (text, action) ->
                val idx = i + 5
                val bg = if (text == "符号" || text == "换行") specialKeyBackgroundColor else keyBackgroundColor
                val w = when (text) {
                    "空格" -> 1.8f
                    "123", "ABC" -> 0.7f
                    else -> 1f
                }
                Box(
                    modifier = Modifier.weight(w).fillMaxSize().padding(1.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (pressedButton == idx) Color(0x40000000) else bg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text, color = keyTextColor, fontSize = 16.sp,
                        fontWeight = if (text.length <= 1) FontWeight.Normal else FontWeight.Medium,
                        textAlign = TextAlign.Center)
                }
            }
        }

        key(dragVersion) {
            Canvas(Modifier.fillMaxSize()) {
                renderStrokes(strokes, currentStrokePoints, Color(0xFF333333))
            }
        }

        val buttonActions = remember {
            listOf(
                listOf("，" to "，", "。" to "。", "？" to "？", "！" to "！", "删除" to "delete"),
                emptyList()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val sx = down.position.x
                        val sy = down.position.y
                        var dragged = false

                        val w = size.width.toFloat()
                        val h = size.height.toFloat()

                        pressedButton = when {
                            sx >= w - sheetWPx && sy < h - barHPx -> {
                                val cellH = (h - barHPx) / 5f
                                ((sy / cellH).toInt().coerceIn(0, 4))
                            }
                            sy >= h - barHPx -> {
                                val seg = w / 5.2f
                                val btnIdx = when {
                                    sx < seg -> 0; sx < seg * 1.7f -> 1
                                    sx < seg * 3.5f -> 2; sx < seg * 4.2f -> 3
                                    else -> 4
                                }
                                btnIdx + 5
                            }
                            else -> -1
                        }

                        do {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull() ?: break

                            if (ch.pressed) {
                                ch.consume()
                                if (!dragged) {
                                    val dist = (ch.position - down.position).getDistance()
                                    if (dist > 12f) {
                                        lastStrokeEndMs = 0L
                                        dragged = true
                                        pressedButton = -1
                                        currentStrokePoints = listOf(StrokePoint(sx, sy))
                                        dragVersion++
                                    }
                                } else {
                                    currentStrokePoints = currentStrokePoints + StrokePoint(ch.position.x, ch.position.y)
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
                                    onStrokeEnd()
                                    if (strokes.isNotEmpty()) {
                                        scope.launch { runPrediction() }
                                    }
                                } else {
                                        if (sx >= w - sheetWPx && sy < h - barHPx) {
                                        val cellH = (h - barHPx) / 5f
                                        val idx = (sy / cellH).toInt().coerceIn(0, 4)
                                        onButtonFeedback?.invoke(listOf("，", "。", "？", "！", "delete")[idx])
                                        onKeyPress(listOf("，", "。", "？", "！", "delete")[idx])
                                    } else if (sy >= h - barHPx) {
                                        val seg = w / 5.2f
                                        val idx = when {
                                            sx < seg -> 0; sx < seg * 1.7f -> 1
                                            sx < seg * 3.5f -> 2; sx < seg * 4.2f -> 3
                                            else -> 4
                                        }
                                        onButtonFeedback?.invoke(listOf("symbol", "number", "space", "ime_switch", "enter")[idx])
                                        onKeyPress(listOf("symbol", "number", "space", "ime_switch", "enter")[idx])
                                    }
                                }
                                pressedButton = -1
                                break
                            }
                        } while (true)
                    }
                }
        )
    }
}
