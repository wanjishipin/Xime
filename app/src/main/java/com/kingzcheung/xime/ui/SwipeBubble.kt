package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ui.theme.KeyBackground
import com.kingzcheung.xime.ui.theme.KeyBackgroundDark
import kotlin.math.roundToInt

private val BubbleBodyHeight = KeyboardDimensions.BubbleHeightDown
private val BubblePointerHeight = KeyboardDimensions.BubblePointerHeight
private val BubbleCornerRadius = KeyboardDimensions.BubbleCornerRadius
private val BubbleScreenMargin = 4.dp

/**
 * 绘制倒"凸"字形气泡：
 *
 *   ┌──────────────────────┐  ← 宽体 rounded rect，居中于 pointer
 *   │        text          │
 *   └──────┬──────────┬────┘  ← 两边有"肩膀"过渡到窄体
 *          │          │         ← 窄体 rounded rect，覆盖按键
 *          └──────────┘
 *
 * 边缘情况：当 body 被 clamp 到屏幕边缘时，pointer 仍然居中于按键，
 * 左右"肩膀"可能不对称，但 Path fill 能正确处理凹多边形。
 */
private fun DrawScope.drawInvertedConvexShape(
    bodyLeft: Float, bodyWidth: Float, bodyHeight: Float,
    pointerLeft: Float, pointerWidth: Float, pointerHeight: Float,
    cornerRadius: Float, color: Color,
    isLeftFlush: Boolean = false,
    isRightFlush: Boolean = false
) {
    val bodyRight = bodyLeft + bodyWidth
    val bodyBottom = bodyHeight
    val pointerRight = pointerLeft + pointerWidth
    val pointerBottom = bodyBottom + pointerHeight

    val r = cornerRadius.coerceAtMost(bodyWidth / 2f).coerceAtMost(bodyHeight / 2f)
    val pr = cornerRadius.coerceAtMost(pointerWidth / 2f).coerceAtMost(pointerHeight / 2f)

    val path = Path().apply {
        // 顺时针从主体左上角开始
        moveTo(bodyLeft + r, 0f)

        // ── 主体上边 ──
        lineTo(bodyRight - r, 0f)
        quadraticBezierTo(bodyRight, 0f, bodyRight, r)

        // ── 主体右边 ──
        if (isRightFlush) {
            // 贴右边缘：直角到底边，跳过圆角和肩膀，直接进 pointer 右上角
            lineTo(bodyRight, bodyBottom)
            quadraticBezierTo(pointerRight, bodyBottom, pointerRight, bodyBottom + pr)
        } else {
            lineTo(bodyRight, bodyBottom - r)
            quadraticBezierTo(bodyRight, bodyBottom, bodyRight - r, bodyBottom)
            // ── 右下"肩膀"（← 向左走） ──
            lineTo(pointerRight + pr, bodyBottom)
            quadraticBezierTo(pointerRight, bodyBottom, pointerRight, bodyBottom + pr)
        }

        // ── pointer 右边 ──
        lineTo(pointerRight, pointerBottom - pr)
        quadraticBezierTo(pointerRight, pointerBottom, pointerRight - pr, pointerBottom)

        // ── pointer 底边（← 向左走） ──
        lineTo(pointerLeft + pr, pointerBottom)
        quadraticBezierTo(pointerLeft, pointerBottom, pointerLeft, pointerBottom - pr)

        // ── pointer 左边（↑ 向上走） ──
        lineTo(pointerLeft, bodyBottom + pr)

        if (isLeftFlush) {
            // 贴左边缘：跳过肩膀和主体左下圆角，直接直上走主体左边
            lineTo(bodyLeft, r)
            quadraticBezierTo(bodyLeft, 0f, bodyLeft + r, 0f)
        } else {
            // ── pointer 左上角（← 向左上走凸圆弧） ──
            quadraticBezierTo(pointerLeft, bodyBottom, pointerLeft - pr, bodyBottom)
            // ── 左下"肩膀"（← 沿 bodyBottom 向左走） ──
            lineTo(bodyLeft + r, bodyBottom)
            // ── 主体左下圆角（← 再向左上走凸圆弧） ──
            quadraticBezierTo(bodyLeft, bodyBottom, bodyLeft, bodyBottom - r)
            // ── 主体左边（↑ 向上走） ──
            lineTo(bodyLeft, r)
            quadraticBezierTo(bodyLeft, 0f, bodyLeft + r, 0f)
        }

        close()
    }
    drawPath(path, color)
}

@Composable
fun SwipeBubble(
    swipeState: SwipeState,
    keyBounds: Rect,
    isDarkTheme: Boolean,
    keyWidth: Float,
    keyboardWidth: Float,
    modifier: Modifier = Modifier
) {
    val shouldShowBubble = swipeState.isSwiping || swipeState.isPressed
    val displayText = if (swipeState.isPressed) swipeState.pressedText else swipeState.swipeText
    if (!shouldShowBubble || displayText == null) return

    val density = LocalDensity.current
    val context = LocalContext.current

    val bgColor = if (swipeState.isDanger) {
        if (swipeState.isSwipeDown) Color(0xFF1A73E8) else Color(0xFFD93025)
    } else if (isDarkTheme) KeyBackgroundDark else KeyBackground
    val textColor = if (swipeState.isDanger) Color.White
    else if (isDarkTheme) Color(0xFFE8EAED) else Color(0xFF202124)

    // ── 尺寸（px） ──
    val bodyHeightPx = with(density) { BubbleBodyHeight.toPx() }
    val pointerHeightPx = with(density) { BubblePointerHeight.toPx() }
    val cornerRadiusPx = with(density) { BubbleCornerRadius.toPx() }
    val screenMarginPx = with(density) { BubbleScreenMargin.toPx() }
    val minBodyWidthPx = with(density) { 104.dp.toPx() } // 64dp defaultMinSize + 20dp*2 padding
    val keyWidthPx = keyWidth
    val totalHeightPx = bodyHeightPx + pointerHeightPx

    // ── 测量文本内容宽度（上一帧结果），用于当前帧 bodyWidth ──
    var measuredWidth by remember { mutableStateOf(0f) }
    val bodyWidth = maxOf(measuredWidth, minBodyWidthPx)

    // ── pointer 居中于按键 ──
    val pointerCenterX = keyBounds.left + keyBounds.width / 2f

    // ── body 居中于 pointerCenterX，但 clamp 到键盘边界内 ──
    val idealBodyLeft = pointerCenterX - bodyWidth / 2f
    val bodyLeft = idealBodyLeft.coerceIn(
        screenMarginPx,
        keyboardWidth - bodyWidth - screenMarginPx
    )
    val bodyRight = bodyLeft + bodyWidth

    // ── pointer 位置 ──
    //    默认居中于按键；靠边一侧与 body 对齐，消除肩膀间隙 ──
    val isLeftClamped = idealBodyLeft < screenMarginPx
    val isRightClamped = idealBodyLeft > keyboardWidth - bodyWidth - screenMarginPx
    val basePointerLeft = pointerCenterX - keyWidthPx / 2f
    val (pointerLeft, pointerRight) = when {
        isLeftClamped && isRightClamped -> bodyLeft to bodyRight
        isLeftClamped -> bodyLeft to (bodyLeft + keyWidthPx)
        isRightClamped -> (bodyRight - keyWidthPx) to bodyRight
        else -> basePointerLeft to (basePointerLeft + keyWidthPx)
    }

    // ── Box 容器同时包裹 body 和 pointer ──
    val boxLeft = minOf(bodyLeft, pointerLeft)
    val boxRight = maxOf(bodyRight, pointerRight)
    val boxWidth = boxRight - boxLeft
    // 窄体从按键顶部开始，覆盖整个按键
    val boxTop = keyBounds.top - bodyHeightPx

    // ── Box 内部相对坐标 ──
    val bodyLeftInBox = bodyLeft - boxLeft
    val pointerLeftInBox = pointerLeft - boxLeft

    // ── dp 值（Modifier 需要） ──
    val boxWidthDp = with(density) { boxWidth.toDp() }
    val totalHeightDp = with(density) { totalHeightPx.toDp() }

    val chaiFontFamily = remember {
        FontFamily(
            androidx.compose.ui.text.font.Typeface(
                android.graphics.Typeface.createFromAsset(
                    context.assets, "ChaiPUA-0.2.7-snow.ttf"
                )
            )
        )
    }

    Box(
        modifier = modifier
            .offset { IntOffset(boxLeft.roundToInt(), boxTop.roundToInt()) }
            .width(boxWidthDp)
            .height(totalHeightDp)
            .shadow(
                4.dp, RoundedCornerShape(BubbleCornerRadius),
                ambientColor = Color(0x22000000), spotColor = Color(0x22000000)
            )
            .drawBehind {
                drawInvertedConvexShape(
                    bodyLeft = bodyLeftInBox,
                    bodyWidth = bodyWidth,
                    bodyHeight = bodyHeightPx,
                    pointerLeft = pointerLeftInBox,
                    pointerWidth = keyWidthPx,
                    pointerHeight = pointerHeightPx,
                    cornerRadius = cornerRadiusPx,
                    color = bgColor,
                    isLeftFlush = isLeftClamped,
                    isRightFlush = isRightClamped
                )
            }
    ) {
        Box(
            modifier = Modifier
                .onGloballyPositioned { coordinates ->
                    // 测量实际内容宽度，更新下一帧的 bodyWidth
                    measuredWidth = coordinates.size.width.toFloat()
                }
                .align(Alignment.TopCenter)
                .padding(horizontal = 20.dp)
                .defaultMinSize(minWidth = 64.dp)
                .height(BubbleBodyHeight),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayText,
                color = textColor,
                fontSize = 14.sp,
                fontFamily = chaiFontFamily,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                softWrap = false
            )
        }
    }
}