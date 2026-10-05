package com.kingzcheung.xime.keyboard

import androidx.compose.ui.unit.dp

/**
 * 键盘统一尺寸配置
 * 所有按键、气泡组件共享这些尺寸，确保视觉一致�?
 */
object KeyboardDimensions {
    // 按键尺寸
    val KeyHeight = 44.dp

    val BubbleHeightDown = 40.dp  // 下滑气泡主体高度
    val BubbleCornerRadius = 8.dp
    
    // 气泡底部pointer（覆盖按键的部分�?
    // 注意：按键实际宽度由weight决定，这里使用估算�?
    // 10键布局中，每个按键约占屏幕宽度�?/10减去间距
    val BubblePointerHeight = KeyHeight  // 与按键同高，确保完全覆盖

    val RowSpacing = 0.dp
}