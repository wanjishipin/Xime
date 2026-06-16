package com.kingzcheung.xime.ui.keyboard

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.KeyboardDimensions
import com.kingzcheung.xime.settings.SettingsPreferences

private val BubbleBodyHeight = KeyboardDimensions.BubbleHeightDown
private val BubblePointerHeight = KeyboardDimensions.BubblePointerHeight
private val BubbleCornerRadius = KeyboardDimensions.BubbleCornerRadius
private val BubbleScreenMargin = 4.dp

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
    val chaiTypeface: Typeface,
    val shadowRadiusPx: Float,
    val textSizePx: Float,
    val selectedFontSizePx: Float,
    val normalFontSizePx: Float,
    val selectedBgRadiusPx: Float,
)

@Composable
fun rememberSwipeBubbleDrawData(
    swipeState: SwipeState,
    keyBounds: Rect,
    isDarkTheme: Boolean,
    keyWidth: Float,
    keyboardWidth: Float,
): BubbleDrawData? {
    val context = LocalContext.current
    val showPressBubble = SettingsPreferences.shouldShowPressBubble(context)
    if (!swipeState.isSwiping && !(showPressBubble && swipeState.isPressed) && !swipeState.isLongPress) return null

    val isLongPressMode = swipeState.isLongPress && swipeState.longPressItems.isNotEmpty()
    val displayText = if (isLongPressMode) null
        else if (swipeState.isPressed) swipeState.pressedText
        else swipeState.swipeText
    if (!isLongPressMode && displayText == null) return null

    val density = LocalDensity.current
    val bodyHeightPx = with(density) { BubbleBodyHeight.toPx() }
    val pointerHeightPx = with(density) { (BubblePointerHeight + 5.dp).toPx() }
    val cornerRadiusPx = with(density) { BubbleCornerRadius.toPx() }
    val screenMarginPx = with(density) { BubbleScreenMargin.toPx() }
    val keyWidthPx = keyWidth
    val minBodyWidthPx = keyWidthPx * 1.8f
    val totalHeightPx = bodyHeightPx + pointerHeightPx
    val shadowRadiusPx = with(density) { 4.dp.toPx() }

    val bgColor = (if (swipeState.isDanger) {
        if (swipeState.isSwipeDown) Color(0xFF1A73E8) else Color(0xFFD93025)
    } else if (isDarkTheme) com.kingzcheung.xime.ui.theme.KeyBackgroundDark
    else com.kingzcheung.xime.ui.theme.KeyBackground).toArgb()
    val textColor = (if (swipeState.isDanger) Color.White
    else if (isDarkTheme) Color(0xFFE8EAED) else Color(0xFF202124)).toArgb()

    val chaiTypeface = remember {
        Typeface.createFromAsset(context.assets, "ChaiPUA-0.2.7-snow.ttf")
    }

    val textPaint = remember {
        Paint().apply {
            textSize = with(density) { 14.sp.toPx() }
            isAntiAlias = true
        }
    }

    val bodyWidth = if (isLongPressMode) {
        maxOf(swipeState.longPressItems.size, 3) * keyWidthPx
    } else {
        maxOf(textPaint.measureText(displayText!!) + with(density) { 20.dp.toPx() }, minBodyWidthPx)
    }

    val textSizePx = with(density) { 14.sp.toPx() }
    val selectedFontSizePx = with(density) { 18.sp.toPx() }
    val normalFontSizePx = with(density) { 14.sp.toPx() }
    val selectedBgRadiusPx = with(density) { 6.dp.toPx() }

    val pointerCenterX = keyBounds.left + keyBounds.width / 2f
    val bodyLeft = (pointerCenterX - bodyWidth / 2f).coerceIn(
        screenMarginPx,
        maxOf(screenMarginPx, keyboardWidth - bodyWidth - screenMarginPx)
    )
    val bodyRight = bodyLeft + bodyWidth
    val pointerLeft = pointerCenterX - keyWidthPx / 2f
    val pointerRight = pointerLeft + keyWidthPx
    val boxLeft = minOf(bodyLeft, pointerLeft)
    val boxTop = keyBounds.top + keyBounds.height - totalHeightPx
    val boxRight = maxOf(bodyRight, pointerRight)

    val rightRoom = bodyRight - pointerRight
    val leftRoom = pointerLeft - bodyLeft
    val flushTolerancePx = with(density) { 10.dp.toPx() }
    val isLeftFlush = leftRoom < cornerRadiusPx + flushTolerancePx || kotlin.math.abs(bodyLeft - pointerLeft) < 1f
    val isRightFlush = rightRoom < cornerRadiusPx + flushTolerancePx || kotlin.math.abs(bodyRight - pointerRight) < 1f
    val bodyLeftInBox = bodyLeft - boxLeft
    val pointerLeftInBox = pointerLeft - boxLeft
    val pointerRightInBox = pointerLeftInBox + keyWidthPx
    val pathBodyLeft = if (isLeftFlush && leftRoom < cornerRadiusPx) pointerLeftInBox else bodyLeftInBox
    val pathBodyWidth = (bodyLeftInBox + bodyWidth) - pathBodyLeft

    val paddingPx = with(density) { 10.dp.toPx() }

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
        isLeftFlush = isLeftFlush && leftRoom < cornerRadiusPx,
        isRightFlush = isRightFlush && rightRoom < cornerRadiusPx,
        bgColor = bgColor,
        textColor = textColor,
        displayText = displayText,
        isLongPressMode = isLongPressMode,
        longPressItems = swipeState.longPressItems,
        selectedLongPressIndex = swipeState.selectedLongPressIndex,
        bodyWidth = bodyWidth,
        textStartX = bodyLeftInBox + paddingPx,
        chaiTypeface = chaiTypeface,
        shadowRadiusPx = shadowRadiusPx,
        textSizePx = textSizePx,
        selectedFontSizePx = selectedFontSizePx,
        normalFontSizePx = normalFontSizePx,
        selectedBgRadiusPx = selectedBgRadiusPx,
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

    return Path().apply {
        moveTo(bodyLeft + r, 0f)
        lineTo(bodyRight - r, 0f)
        quadTo(bodyRight, 0f, bodyRight, r)

        if (isRightFlush) {
            lineTo(bodyRight, bodyBottom)
            quadTo(pointerRight, bodyBottom, pointerRight, bodyBottom + pr)
        } else {
            lineTo(bodyRight, bodyBottom - r)
            quadTo(bodyRight, bodyBottom, bodyRight - r, bodyBottom)
            lineTo(pointerRight + pr, bodyBottom)
            quadTo(pointerRight, bodyBottom, pointerRight, bodyBottom + pr)
        }

        lineTo(pointerRight, pointerBottom - pr)
        quadTo(pointerRight, pointerBottom, pointerRight - pr, pointerBottom)
        lineTo(pointerLeft + pr, pointerBottom)
        quadTo(pointerLeft, pointerBottom, pointerLeft, pointerBottom - pr)
        lineTo(pointerLeft, bodyBottom + pr)

        if (isLeftFlush) {
            lineTo(pointerLeft, bodyBottom)
            lineTo(bodyLeft, bodyBottom)
            lineTo(bodyLeft, r)
            quadTo(bodyLeft, 0f, bodyLeft + r, 0f)
        } else {
            quadTo(pointerLeft, bodyBottom, pointerLeft - pr, bodyBottom)
            lineTo(bodyLeft + r, bodyBottom)
            quadTo(bodyLeft, bodyBottom, bodyLeft, bodyBottom - r)
            lineTo(bodyLeft, r)
            quadTo(bodyLeft, 0f, bodyLeft + r, 0f)
        }

        close()
    }
}

fun DrawScope.drawSwipeBubble(data: BubbleDrawData) {
    val path = buildBubblePath(data)

    drawIntoCanvas { composeCanvas ->
        val canvas = composeCanvas.nativeCanvas
        canvas.save()
        canvas.translate(data.boxLeft, data.boxTop)

        val fillPaint = Paint().apply {
            color = data.bgColor
            isAntiAlias = true
            setShadowLayer(data.shadowRadiusPx, 0f, 0f, android.graphics.Color.argb(0x44, 0, 0, 0))
        }
        canvas.drawPath(path, fillPaint)

        if (data.isLongPressMode) {
            canvas.save()
            canvas.clipRect(0f, 0f, data.bodyWidth, data.bodyHeightPx)
            val accentColor = android.graphics.Color.argb(0xFF, 0x8F, 0x73, 0xE2)
            val selectedBgColor = android.graphics.Color.argb(0x33, 0x8F, 0x73, 0xE2)
            val cellWidth = data.bodyWidth / data.longPressItems.size

            data.longPressItems.forEachIndexed { index, item ->
                val itemLeft = index * cellWidth
                if (index == data.selectedLongPressIndex) {
                    val bgPaint = Paint().apply {
                        color = selectedBgColor
                        isAntiAlias = true
                    }
                    val r = minOf(data.selectedBgRadiusPx, cellWidth / 2f)
                    canvas.drawRoundRect(
                        itemLeft, 0f, itemLeft + cellWidth, data.bodyHeightPx, r, r, bgPaint
                    )
                }
                val fontSize = if (index == data.selectedLongPressIndex) data.selectedFontSizePx else data.normalFontSizePx
                val labelPaint = Paint().apply {
                    color = if (index == data.selectedLongPressIndex) accentColor else data.textColor
                    textSize = fontSize
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                val textY = data.bodyHeightPx / 2f - (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f
                canvas.drawText(item, itemLeft + cellWidth / 2f, textY, labelPaint)
            }
            canvas.restore()
        } else if (data.displayText != null) {
            canvas.save()
            canvas.clipRect(0f, 0f, data.bodyWidth, data.bodyHeightPx)
            val textPaint = Paint().apply {
                color = data.textColor
                textSize = data.textSizePx
                textAlign = Paint.Align.CENTER
                typeface = data.chaiTypeface
                isAntiAlias = true
            }
            val textCenterX = data.pathBodyLeft + data.pathBodyWidth / 2f
            val textY = data.bodyHeightPx / 2f - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
            canvas.drawText(data.displayText, textCenterX, textY, textPaint)
            canvas.restore()
        }

        canvas.restore()
    }
}
