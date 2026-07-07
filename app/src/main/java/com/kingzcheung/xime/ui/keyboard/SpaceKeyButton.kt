package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SpaceKeyButton(
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    schemaName: String = "",
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    isVoiceMode: Boolean = false,
    onVoiceModeChange: ((Boolean) -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    var isPressed by remember { mutableStateOf(false) }
    val longPressTimeout = 400L
    val scope = rememberCoroutineScope()
    
    Box(
        modifier = modifier
            .height((44 * LocalStretchFactor.current).dp)
            .then(
                if (shadowEnabled) Modifier.shadow(shadowElevation, RoundedCornerShape(LocalKeyCornerRadius.current), ambientColor = Color(0x80000000), spotColor = Color(0x80000000))
                else Modifier
            )
            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
            .background(
                if (isPressed) backgroundColor.copy(alpha = 0.7f)
                else backgroundColor
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    onPress?.invoke()
                    
                    var longPressTriggered = false
                    val longPressJob = scope.launch {
                        delay(longPressTimeout)
                        longPressTriggered = true
                        onVoiceModeChange?.invoke(true)
                    }
                    
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        longPressJob.cancel()
                        
                        if (longPressTriggered) {
                            onVoiceModeChange?.invoke(false)
                        } else {
                            onClick()
                        }
                        
                        isPressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // UI 显示由外部 isVoiceMode 控制
        if (isVoiceMode) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "语音输入",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Text(
                text = schemaName,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            
            Text(
                text = "空格",
                color = textColor.copy(alpha = 0.3f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Start,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 2.dp)
            )
        }
    }
}