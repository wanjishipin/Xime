package com.kingzcheung.xime.ui.keyboard

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.KeyboardDimensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BubbleBodyHeight = KeyboardDimensions.BubbleHeightDown
private val BubbleCornerRadius = KeyboardDimensions.BubbleCornerRadius
private val BubbleScreenMargin = 4.dp

private val bubblePath = Path()
private val bubbleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val bubbleLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val SHADOW_COLOR = android.graphics.Color.argb(0x26, 0, 0, 0)

data class BubbleDrawData(
    val boxLeft: Float,
    val boxTop: Float,
    val pathBodyLeft: Float,
    val pathBodyWidth: Float,
    val pointerLeftInBox: Float,
    val keyWidthPx: Float,
    val bodyHeightPx: Float,
    val pointerHeightPx: Float,
    val cornerRadiusPx: Float,
    val isLeftFlush: Boolean,
    val isRightFlush: Boolean,
    val bgColor: Int,
    val textColor: Int,
    val displayText: String?,
    val isLongPressMode: Boolean,
    val longPressItems: List<String>,
    val selectedLongPressIndex: Int,
    val bodyWidth: Float,
    val textStartX: Float,
    val keyLabelTypeface: Typeface,
    val shadowRadiusPx: Float,
    val textSizePx: Float,
    val selectedFontSizePx: Float,
    val normalFontSizePx: Float,
    val selectedBgRadiusPx: Float,
    val longPressIconBitmaps: List<Bitmap> = emptyList(),
    val accentArgb: Int = 0xFF8F73E2.toInt(),
)

/** 按压气泡抬起后的滞留时长：随抬起瞬间消失看不清键位提示（主流输入法约 50~80ms），取中值。 */
private const val PRESS_BUBBLE_RELEASE_DELAY_MS = 60L

/**
 * 各布局共享的气泡状态持有器。
 *
 * 封装"按压气泡抬起后短暂滞留"逻辑：抬起瞬间不清空状态，而是保留气泡内容与位置
 * [PRESS_BUBBLE_RELEASE_DELAY_MS] 毫秒再消失；期间任意新手势（新键按压/滑动/长按）
 * 会立即取消滞留并切换为新气泡，快速连打无延迟感。
 * 仅对按压气泡滞留——滑动选择与长按选择的气泡在松手时语义上已结束（候选已提交），立即消失。
 */
class SwipeBubbleController(private val scope: CoroutineScope) {
    var state by mutableStateOf(SwipeState())
        private set
    var keyBounds by mutableStateOf(Rect(0f, 0f, 0f, 0f))
        private set
    private var releaseJob: Job? = null

    fun update(newState: SwipeState, bounds: Rect) {
        releaseJob?.cancel()
        releaseJob = null
        val prev = state
        val gestureActive = newState.isSwiping || newState.isPressed || newState.isLongPress
        if (!gestureActive &&
            prev.isPressed && prev.pressedText != null &&
            !prev.isSwiping && !prev.isLongPress
        ) {
            state = prev
            keyBounds = bounds
            releaseJob = scope.launch {
                delay(PRESS_BUBBLE_RELEASE_DELAY_MS)
                state = SwipeState()
            }
            return
        }
        state = newState
        keyBounds = bounds
    }
}

@Composable
fun rememberSwipeBubbleController(): SwipeBubbleController {
    val scope = rememberCoroutineScope()
    return remember { SwipeBubbleController(scope) }
}

@Composable
fun rememberSwipeBubbleDrawData(
    swipeState: SwipeState,
    keyBounds: Rect,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    accentColor: Color = Color(0xFF8F73E2),
    keyWidth: Float,
    keyboardWidth: Float,
): BubbleDrawData? {
    val context = LocalContext.current
    // 点按气泡恒开：非滑动、非点按、非长按时不绘制
    if (!swipeState.isSwiping && !swipeState.isPressed && !swipeState.isLongPress) return null

    val isLongPressMode = swipeState.isLongPress && swipeState.longPressItems.isNotEmpty()
    val displayText = if (isLongPressMode) null
        else if (swipeState.isPressed) swipeState.pressedText
        else swipeState.swipeText
    if (!isLongPressMode && displayText.isNullOrEmpty()) return null

    val density = LocalDensity.current
    val referenceHeightDp =
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) 44f else 56f
    val contentScale = adaptiveKeyContentScale(
        keyHeightDp = keyBounds.height / density.density,
        referenceHeightDp = referenceHeightDp,
    )
    val bubbleScale = adaptiveBubbleScale(contentScale)
    val bodyHeightPx = with(density) { BubbleBodyHeight.toPx() } * bubbleScale
    // 尖端完整覆盖按下的按键（与按键同高），宽体锚定按键顶部悬在上方（见 boxTop）。
    // 全部基于真实按键 bounds 计算，不再用 KeyHeight 估算值——
    // 键盘高度被调大时估算失准，宽体会下沉进按键被手指挡住。
    val pointerHeightPx = keyBounds.height
    val cornerRadiusPx = with(density) { BubbleCornerRadius.toPx() } * bubbleScale
    val screenMarginPx = with(density) { BubbleScreenMargin.toPx() }
    val keyWidthPx = keyWidth
    val minBodyWidthPx = keyWidthPx * 1.8f
    val shadowRadiusPx = with(density) { 4.dp.toPx() } * bubbleScale

    val accentArgb = accentColor.toArgb()
    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)
    val bgColor = (if (swipeState.isDanger) {
        if (swipeState.isSwipeDown) Color(0xFF1A73E8) else Color(0xFFD93025)
    } else keyBackgroundColor).toArgb()
    val textColor = (if (swipeState.isDanger) Color.White else keyTextColor).toArgb()

    val keyLabelTypeface = AppFonts.keyLabelTypeface

    val textPaint = remember(bubbleScale) {
        Paint().apply {
            textSize = with(density) { 16.sp.toPx() } * bubbleScale
            isAntiAlias = true
        }
    }

    val bodyWidth = if (isLongPressMode) {
        val cellMin = if (swipeState.longPressDrawableIds.isNotEmpty())
            swipeState.longPressItems.size
        else
            maxOf(swipeState.longPressItems.size, 3)
        cellMin * keyWidthPx
    } else {
        maxOf(textPaint.measureText(displayText!!) + with(density) { 20.dp.toPx() } * bubbleScale, minBodyWidthPx)
    }

    val textSizePx = with(density) { 16.sp.toPx() } * bubbleScale
    val selectedFontSizePx = with(density) { 20.sp.toPx() } * bubbleScale
    val normalFontSizePx = with(density) { 16.sp.toPx() } * bubbleScale
    val selectedBgRadiusPx = with(density) { 6.dp.toPx() } * bubbleScale

    val pointerCenterX = keyBounds.left + keyBounds.width / 2f
    val bodyLeft = (pointerCenterX - bodyWidth / 2f).coerceIn(
        screenMarginPx,
        maxOf(screenMarginPx, keyboardWidth - bodyWidth - screenMarginPx)
    )
    val bodyRight = bodyLeft + bodyWidth
    val pointerLeft = pointerCenterX - keyWidthPx / 2f
    val pointerRight = pointerLeft + keyWidthPx
    val boxLeft = minOf(bodyLeft, pointerLeft)
    // 宽体（显示文字的主体）底部对齐按键顶部，悬在按键正上方不被手指遮挡；
    // 尖端从宽体底部向下延伸 pointerHeightPx（≈按键上半部）指示归属键。
    val boxTop = keyBounds.top - bodyHeightPx
    val boxRight = maxOf(bodyRight, pointerRight)

    val rightRoom = bodyRight - pointerRight
    val leftRoom = pointerLeft - bodyLeft
    val flushTolerancePx = with(density) { 10.dp.toPx() }
    val isLeftFlush = leftRoom < cornerRadiusPx + flushTolerancePx || kotlin.math.abs(bodyLeft - pointerLeft) < 1f
    val isRightFlush = rightRoom < cornerRadiusPx + flushTolerancePx || kotlin.math.abs(bodyRight - pointerRight) < 1f
    val bodyLeftInBox = bodyLeft - boxLeft
    val pointerLeftInBox = pointerLeft - boxLeft
    val pointerRightInBox = pointerLeftInBox + keyWidthPx
    val pathBodyLeft = if (isLeftFlush && leftRoom <= cornerRadiusPx) pointerLeftInBox else bodyLeftInBox
    val pathBodyWidth = (if (isRightFlush && rightRoom <= cornerRadiusPx) pointerRightInBox else (bodyLeftInBox + bodyWidth)) - pathBodyLeft

    val paddingPx = with(density) { 10.dp.toPx() } * bubbleScale

    val longPressIconBitmaps = remember(swipeState.longPressDrawableIds, textColor) {
        swipeState.longPressDrawableIds.mapNotNull { id ->
            val drawable = context.resources.getDrawable(id, context.theme)?.mutate() ?: return@mapNotNull null
            drawable.setTint(textColor)
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bmpCanvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(bmpCanvas)
            bitmap
        }
    }

    return BubbleDrawData(
        boxLeft = boxLeft,
        boxTop = boxTop,
        pathBodyLeft = pathBodyLeft,
        pathBodyWidth = pathBodyWidth,
        pointerLeftInBox = pointerLeftInBox,
        keyWidthPx = keyWidthPx,
        bodyHeightPx = bodyHeightPx,
        pointerHeightPx = pointerHeightPx,
        cornerRadiusPx = cornerRadiusPx,
        isLeftFlush = isLeftFlush && leftRoom <= cornerRadiusPx,
        isRightFlush = isRightFlush && rightRoom <= cornerRadiusPx,
        bgColor = bgColor,
        textColor = textColor,
        displayText = displayText,
        isLongPressMode = isLongPressMode,
        longPressItems = swipeState.longPressItems,
        selectedLongPressIndex = swipeState.selectedLongPressIndex,
        bodyWidth = bodyWidth,
        textStartX = bodyLeftInBox + paddingPx,
        keyLabelTypeface = keyLabelTypeface,
        shadowRadiusPx = shadowRadiusPx,
        textSizePx = textSizePx,
        selectedFontSizePx = selectedFontSizePx,
        normalFontSizePx = normalFontSizePx,
        selectedBgRadiusPx = selectedBgRadiusPx,
        longPressIconBitmaps = longPressIconBitmaps,
        accentArgb = accentArgb,
    )
}

private fun buildBubblePath(data: BubbleDrawData): Path {
    val bodyLeft = data.pathBodyLeft
    val bodyWidth = data.pathBodyWidth
    val bodyHeight = data.bodyHeightPx
    val pointerLeft = data.pointerLeftInBox
    val pointerWidth = data.keyWidthPx
    val pointerHeight = data.pointerHeightPx
    val cornerRadius = data.cornerRadiusPx
    val isLeftFlush = data.isLeftFlush
    val isRightFlush = data.isRightFlush

    val bodyRight = bodyLeft + bodyWidth
    val bodyBottom = bodyHeight
    val pointerRight = pointerLeft + pointerWidth
    val pointerBottom = bodyBottom + pointerHeight

    val r = cornerRadius.coerceAtMost(bodyWidth / 2f).coerceAtMost(bodyHeight / 2f)
    val pr = cornerRadius.coerceAtMost(pointerWidth / 2f).coerceAtMost(pointerHeight / 2f)

    bubblePath.rewind()
    bubblePath.moveTo(bodyLeft + r, 0f)
    bubblePath.lineTo(bodyRight - r, 0f)
    bubblePath.quadTo(bodyRight, 0f, bodyRight, r)

    if (isRightFlush) {
        bubblePath.lineTo(bodyRight, bodyBottom)
        bubblePath.quadTo(pointerRight, bodyBottom, pointerRight, bodyBottom + pr)
    } else {
        bubblePath.lineTo(bodyRight, bodyBottom - r)
        bubblePath.quadTo(bodyRight, bodyBottom, bodyRight - r, bodyBottom)
        bubblePath.lineTo(pointerRight + pr, bodyBottom)
        bubblePath.quadTo(pointerRight, bodyBottom, pointerRight, bodyBottom + pr)
    }

    bubblePath.lineTo(pointerRight, pointerBottom - pr)
    bubblePath.quadTo(pointerRight, pointerBottom, pointerRight - pr, pointerBottom)
    bubblePath.lineTo(pointerLeft + pr, pointerBottom)
    bubblePath.quadTo(pointerLeft, pointerBottom, pointerLeft, pointerBottom - pr)
    bubblePath.lineTo(pointerLeft, bodyBottom + pr)

    if (isLeftFlush) {
        bubblePath.lineTo(pointerLeft, bodyBottom)
        bubblePath.lineTo(bodyLeft, bodyBottom)
        bubblePath.lineTo(bodyLeft, r)
        bubblePath.quadTo(bodyLeft, 0f, bodyLeft + r, 0f)
    } else {
        bubblePath.quadTo(pointerLeft, bodyBottom, pointerLeft - pr, bodyBottom)
        bubblePath.lineTo(bodyLeft + r, bodyBottom)
        bubblePath.quadTo(bodyLeft, bodyBottom, bodyLeft, bodyBottom - r)
        bubblePath.lineTo(bodyLeft, r)
        bubblePath.quadTo(bodyLeft, 0f, bodyLeft + r, 0f)
    }

    bubblePath.close()
    return bubblePath
}

fun DrawScope.drawSwipeBubble(data: BubbleDrawData) {
    val path = buildBubblePath(data)

    drawIntoCanvas { composeCanvas ->
        val canvas = composeCanvas.nativeCanvas
        canvas.save()
        canvas.translate(data.boxLeft, data.boxTop)

        bubbleFillPaint.color = data.bgColor
        bubbleFillPaint.setShadowLayer(data.shadowRadiusPx, 0f, 0f, SHADOW_COLOR)
        canvas.drawPath(path, bubbleFillPaint)

        if (data.isLongPressMode) {
            canvas.save()
            canvas.clipRect(0f, 0f, data.bodyWidth, data.bodyHeightPx)
            val accentColor = data.accentArgb
            val selectedBgColor = android.graphics.Color.argb(
                0x33, android.graphics.Color.red(accentColor),
                android.graphics.Color.green(accentColor), android.graphics.Color.blue(accentColor)
            )
            val cellWidth = data.bodyWidth / data.longPressItems.size

            data.longPressItems.forEachIndexed { index, item ->
                val itemLeft = index * cellWidth
                if (index == data.selectedLongPressIndex) {
                    bubbleBgPaint.color = selectedBgColor
                    val r = minOf(data.selectedBgRadiusPx, cellWidth / 2f)
                    canvas.drawRoundRect(
                        itemLeft, 0f, itemLeft + cellWidth, data.bodyHeightPx, r, r, bubbleBgPaint
                    )
                }
                if (data.longPressIconBitmaps.isNotEmpty() && index < data.longPressIconBitmaps.size) {
                    val icon = data.longPressIconBitmaps[index]
                    val iconSize = data.bodyHeightPx * 0.45f
                    val iconLeft = itemLeft + (cellWidth - iconSize) / 2f
                    val iconTop = (data.bodyHeightPx - iconSize) / 2f
                    canvas.drawBitmap(
                        icon, null,
                        android.graphics.RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize),
                        null
                    )
                } else {
                    val fontSize = if (index == data.selectedLongPressIndex) data.selectedFontSizePx else data.normalFontSizePx
                    bubbleLabelPaint.color = if (index == data.selectedLongPressIndex) accentColor else data.textColor
                    bubbleLabelPaint.textSize = fontSize
                    bubbleLabelPaint.textAlign = Paint.Align.CENTER
                    bubbleLabelPaint.isFakeBoldText = true
                    bubbleLabelPaint.typeface = data.keyLabelTypeface
                    val textY = data.bodyHeightPx / 2f - (bubbleLabelPaint.fontMetrics.ascent + bubbleLabelPaint.fontMetrics.descent) / 2f
                    canvas.drawText(item, itemLeft + cellWidth / 2f, textY, bubbleLabelPaint)
                }
            }
            canvas.restore()
        } else if (data.displayText != null) {
            canvas.save()
            canvas.clipRect(0f, 0f, data.bodyWidth, data.bodyHeightPx)
            bubbleTextPaint.color = data.textColor
            bubbleTextPaint.textSize = data.textSizePx
            bubbleTextPaint.textAlign = Paint.Align.CENTER
            bubbleTextPaint.typeface = data.keyLabelTypeface
            bubbleTextPaint.isFakeBoldText = true
            val textCenterX = data.pathBodyLeft + data.pathBodyWidth / 2f
            val textY = data.bodyHeightPx / 2f - (bubbleTextPaint.fontMetrics.ascent + bubbleTextPaint.fontMetrics.descent) / 2f
            canvas.drawText(data.displayText, textCenterX, textY, bubbleTextPaint)
            canvas.restore()
        }

        canvas.restore()
    }
}
