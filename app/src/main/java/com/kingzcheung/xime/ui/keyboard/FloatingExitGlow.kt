package com.kingzcheung.xime.ui.keyboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 悬浮键盘"拖到底部可停靠"的提示光效（见 ImeKeyboardCallbacks 的
 * onFloatingKeyboardDrag/End 与 service.floatingExitHintState）。
 *
 * **画在键盘内容之上**（服务层根 Box 的最后一个子级）：以窗口底边中点为光心的
 * 径向光 + 底边光核线，颜色取当前键盘主题的强调色——轻扫过键盘底缘与底部留白，
 * 呈现"从底边发光"的观感。纯视觉，不参与触摸（触摸区不含此区域）。
 */
@Composable
fun FloatingExitGlow(
    offsetXdp: Int,
    cardWidthDp: Int,
    /** 卡片底边到窗口底边的当前距离（dp，即拖动钳制的 minY） */
    bottomGapDp: Int,
    /** 光效颜色：调用方传当前主题的强调色（KeyboardThemes.getAccentColor） */
    glowColor: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "floatingExitGlow")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "glowPulse",
    )
    Box(
        modifier = modifier
            .width(cardWidthDp.dp + SIDE_FLARE_DP.dp * 2)
            .height(bottomGapDp.dp + GLOW_RISE_DP.dp)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = 0.50f * pulse),
                            glowColor.copy(alpha = 0.20f * pulse),
                            glowColor.copy(alpha = 0f),
                        ),
                        center = Offset(x = size.width / 2f, y = size.height),
                        radius = size.height * 1.2f,
                    )
                )
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(3.dp)
                .background(glowColor.copy(alpha = 0.85f * pulse))
        )
    }
}

/** 光晕两侧超出卡片宽度的外溢量（dp） */
private const val SIDE_FLARE_DP = 24

/** 光效自窗口底边向上的高度（dp，扫过键盘底缘几行按键） */
private const val GLOW_RISE_DP = 80
