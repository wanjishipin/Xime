package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class KeyboardRowConfig(
    val keyBackgroundColor: Color,
    val keyTextColor: Color,
    val keyboardBackgroundColor: Color = Color.Transparent,
    val fontSize: TextUnit = TextUnit.Unspecified,
    val swipeFontSize: TextUnit = 9.sp,
    val shadowEnabled: Boolean = true,
    val shadowElevation: Dp = 1.dp,
    val shadowShapeRadius: Dp = 8.dp,
)
