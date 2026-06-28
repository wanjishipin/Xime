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
import com.kingzcheung.xime.handwriting.StrokePoint
import com.kingzcheung.xime.handwriting.renderStrokes
import com.kingzcheung.xime.keyboard.KeyboardDimensions
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HandwritingLookupKeyboard(
    keyTextColor: Color,
    specialKeyBgColor: Color,
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
    var currentStrokePoints by remember { mutableStateOf<List<StrokePoint>>(emptyList()) }
    var dragVersion by remember { mutableIntStateOf(0) }
    var lastStrokeEndMs by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { HandwritingEngine.initialize(context) } }
    LaunchedEffect(lastStrokeEndMs) {
        if (lastStrokeEndMs > 0L) {
            delay(1000L); strokes.clear(); dragVersion++
        }
    }
    LaunchedEffect(clearSignal) { strokes.clear(); dragVersion++ }

    suspend fun runPrediction() {
        if (!HandwritingEngine.isInitialized()) return
        val snapshot = strokes.toList()
        if (snapshot.isEmpty()) return
        val pairs = snapshot.map { stroke -> stroke.map { Pair(it.x, it.y) } }
        val result = withContext(Dispatchers.Default) { HandwritingEngine.predict(pairs, 20) }
        onCandidates?.invoke(result)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(keyboardBgColor)
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
                                if (strokes.isNotEmpty()) scope.launch { runPrediction() }
                            }
                            break
                        }
                    } while (true)
                }
            })

        key(dragVersion) {
            Canvas(Modifier.fillMaxSize()) {
                renderStrokes(strokes, currentStrokePoints, keyTextColor)
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
                KeyButton("返回", { onExit() }, specialKeyBgColor, keyTextColor, Modifier.weight(1f), onPress = { onButtonFeedback?.invoke("exit") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                Spacer(Modifier.weight(4f))
                KeyButton("回车", { onKeyPress("enter") }, specialKeyBgColor, keyTextColor, Modifier.weight(1f), onPress = { onButtonFeedback?.invoke("enter") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
            }
        }
    }
}
