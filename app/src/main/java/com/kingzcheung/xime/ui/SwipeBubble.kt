package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
 * 构建倒"凸"字形路径：
 *
 *   ┌──────────────────────┐  ← 宽体 rounded rect
 *   │        text          │
 *   └──────┬──────────┬────┘  ← "肩膀"过渡
 *          │          │         ← 窄体 rounded rect
 *          └──────────┘
 */
private fun buildInvertedConvexPath(
    bodyLeft: Float, bodyWidth: Float, bodyHeight: Float,
    pointerLeft: Float, pointerWidth: Float, pointerHeight: Float,
    cornerRadius: Float,
    isLeftFlush: Boolean = false,
    isRightFlush: Boolean = false
): Path {
    val bodyRight = bodyLeft + bodyWidth
    val bodyBottom = bodyHeight
    val pointerRight = pointerLeft + pointerWidth
    val pointerBottom = bodyBottom + pointerHeight

    val r = cornerRadius.coerceAtMost(bodyWidth / 2f).coerceAtMost(bodyHeight / 2f)
    val pr = cornerRadius.coerceAtMost(pointerWidth / 2f).coerceAtMost(pointerHeight / 2f)

    return Path().apply {
        moveTo(bodyLeft + r, 0f)

        // ── 主体上边 ──
        lineTo(bodyRight - r, 0f)
        quadraticBezierTo(bodyRight, 0f, bodyRight, r)

        // ── 主体右边 ──
        if (isRightFlush) {
            // 直角落底 → 直线进 pointer 右边（无圆角）
            lineTo(bodyRight, bodyBottom)
            lineTo(pointerRight, pointerBottom - pr)
        } else {
            lineTo(bodyRight, bodyBottom - r)
            quadraticBezierTo(bodyRight, bodyBottom, bodyRight - r, bodyBottom)
            lineTo(pointerRight + pr, bodyBottom)
            quadraticBezierTo(pointerRight, bodyBottom, pointerRight, bodyBottom + pr)
            lineTo(pointerRight, pointerBottom - pr)
        }
        quadraticBezierTo(pointerRight, pointerBottom, pointerRight - pr, pointerBottom)

        // ── pointer 底边 ──
        lineTo(pointerLeft + pr, pointerBottom)
        quadraticBezierTo(pointerLeft, pointerBottom, pointerLeft, pointerBottom - pr)

        // ── pointer 左边 ──
        lineTo(pointerLeft, bodyBottom + pr)

        if (isLeftFlush) {
            // 直角落底 → 直线进主体左边（无圆角）
            lineTo(pointerLeft, bodyBottom)
            lineTo(bodyLeft, bodyBottom)
            lineTo(bodyLeft, r)
            quadraticBezierTo(bodyLeft, 0f, bodyLeft + r, 0f)
        } else {
            quadraticBezierTo(pointerLeft, bodyBottom, pointerLeft - pr, bodyBottom)
            lineTo(bodyLeft + r, bodyBottom)
            quadraticBezierTo(bodyLeft, bodyBottom, bodyLeft, bodyBottom - r)
            lineTo(bodyLeft, r)
            quadraticBezierTo(bodyLeft, 0f, bodyLeft + r, 0f)
        }

        close()
    }
}

/**
 * 自定义 Shape，使 Modifier.shadow() 能沿倒"凸"轮廓投射阴影
 */
private class InvertedConvexShape(
    private val builder: (Size) -> Path
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        return Outline.Generic(builder(size))
    }
}

/** 绘制倒"凸"字形气泡 */
private fun DrawScope.drawInvertedConvexShape(
    bodyLeft: Float, bodyWidth: Float, bodyHeight: Float,
    pointerLeft: Float, pointerWidth: Float, pointerHeight: Float,
    cornerRadius: Float, color: Color,
    isLeftFlush: Boolean = false,
    isRightFlush: Boolean = false
) {
    val path = buildInvertedConvexPath(
        bodyLeft, bodyWidth, bodyHeight,
        pointerLeft, pointerWidth, pointerHeight,
        cornerRadius,
        isLeftFlush, isRightFlush
    )
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
    val pointerHeightPx = with(density) { (BubblePointerHeight + 5.dp).toPx() }
    val cornerRadiusPx = with(density) { BubbleCornerRadius.toPx() }
    val screenMarginPx = with(density) { BubbleScreenMargin.toPx() }
    val keyWidthPx = keyWidth
    val minBodyWidthPx = keyWidthPx + with(density) { 24.dp.toPx() }
    val totalHeightPx = bodyHeightPx + pointerHeightPx

    // ── 用 Paint 同步测量文本宽度，避免 constrained layout 的 chicken-and-egg ──
    val textWidthPx = with(density) {
        val paint = android.graphics.Paint().apply {
            textSize = 14.sp.toPx()
            isAntiAlias = true
        }
        paint.measureText(displayText)
    }
    val bodyWidth = maxOf(textWidthPx + with(density) { 20.dp.toPx() }, minBodyWidthPx) // textWidth + 10dp*2 padding

    // ── pointer 居中于按键 ──
    val pointerCenterX = keyBounds.left + keyBounds.width / 2f

    // ── body 居中于 pointerCenterX，但 clamp 到键盘边界内 ──
    val idealBodyLeft = pointerCenterX - bodyWidth / 2f
    val bodyLeft = idealBodyLeft.coerceIn(
        screenMarginPx,
        keyboardWidth - bodyWidth - screenMarginPx
    )
    val bodyRight = bodyLeft + bodyWidth

    // ── pointer 始终居中于按键 ──
    val pointerLeft = pointerCenterX - keyWidthPx / 2f
    val pointerRight = pointerLeft + keyWidthPx

    // ── Box 容器同时包裹 body 和 pointer ──
    val boxLeft = minOf(bodyLeft, pointerLeft)
    val boxRight = maxOf(bodyRight, pointerRight)
    val boxWidth = boxRight - boxLeft
    // 气泡底部与按键底部对齐，窄体覆盖整个按键
    val boxTop = keyBounds.top + keyBounds.height - totalHeightPx

    // ── 检测宽窄体是否对齐（贴边时边缘侧肩膀应为直角直线） ──
    val isLeftFlush = kotlin.math.abs(bodyLeft - pointerLeft) < 1f
    val isRightFlush = kotlin.math.abs(bodyRight - pointerRight) < 1f

    // ── Box 内部相对坐标 ──
    val bodyLeftInBox = bodyLeft - boxLeft
    val pointerLeftInBox = pointerLeft - boxLeft

    // ── dp 值（Modifier 需要） ──
    val boxWidthDp = with(density) { boxWidth.toDp() }
    val totalHeightDp = with(density) { totalHeightPx.toDp() }

    // 自定义 Shape，使阴影沿倒"凸"轮廓投射
    val bubbleShape = remember(bodyLeftInBox, bodyWidth, bodyHeightPx,
        pointerLeftInBox, keyWidthPx, pointerHeightPx, cornerRadiusPx,
        isLeftFlush, isRightFlush) {
        InvertedConvexShape { _ ->
            buildInvertedConvexPath(
                bodyLeft = bodyLeftInBox,
                bodyWidth = bodyWidth,
                bodyHeight = bodyHeightPx,
                pointerLeft = pointerLeftInBox,
                pointerWidth = keyWidthPx,
                pointerHeight = pointerHeightPx,
                cornerRadius = cornerRadiusPx,
                isLeftFlush = isLeftFlush,
                isRightFlush = isRightFlush
            )
        }
    }

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
                10.dp, shape = bubbleShape, clip = false,
                ambientColor = Color(0x88000000), spotColor = Color(0x88000000)
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
                    isLeftFlush = isLeftFlush,
                    isRightFlush = isRightFlush
                )
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 10.dp)
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