package com.kingzcheung.xime.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import kotlin.math.sqrt

@Composable
fun VoiceKeyboardLayout(
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = false,
    themeId: String = "ocean_blue",
    bottomActive: Boolean = false,
    leftActive: Boolean = false,
    rightActive: Boolean = false,
    pluginName: String = "",
    recognitionState: RecognitionState = RecognitionState.IDLE,
    recognizedText: String = "",
    amplitude: Float = 0f
) {
    val accentColor = KeyboardThemes.getAccentColor(themeId, isDarkTheme)
    val inactiveColor = if (isDarkTheme) Color.Gray.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.9f)
    val activeColor = accentColor
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(keyboardBackgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (pluginName.isNotEmpty()) {
                Text(
                    text = pluginName,
                    color = keyTextColor.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            
            val statusText = when (recognitionState) {
                RecognitionState.IDLE -> "长按空格开始说话"
                RecognitionState.LISTENING -> "正在聆听..."
                RecognitionState.PROCESSING -> "正在识别..."
                RecognitionState.ERROR -> "识别出错"
            }
            
            Text(
                text = statusText,
                color = keyTextColor.copy(alpha = 0.8f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            
            if (recognizedText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = recognizedText,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier.size(if (leftActive) 64.dp else 56.dp)
                ) {
                    drawCircle(
                        color = if (leftActive) activeColor else inactiveColor,
                        radius = size.minDimension / 2f
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "撤销",
                    tint = if (leftActive) Color.White else if (isDarkTheme) Color.DarkGray else Color.Gray,
                    modifier = Modifier
                        .size(28.dp)
                        .scale(if (leftActive) 1.15f else 1f)
                )
            }
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = inactiveColor.copy(alpha = 0.3f),
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx())
                    )
                }
                
                AudioSpectrumAnimation(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    isActive = recognitionState == RecognitionState.LISTENING || 
                               recognitionState == RecognitionState.PROCESSING,
                    amplitude = amplitude
                )
            }
            
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier.size(if (rightActive) 64.dp else 56.dp)
                ) {
                    drawCircle(
                        color = if (rightActive) activeColor else inactiveColor,
                        radius = size.minDimension / 2f
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "发送",
                    tint = if (rightActive) Color.White else if (isDarkTheme) Color.DarkGray else Color.Gray,
                    modifier = Modifier
                        .size(28.dp)
                        .scale(if (rightActive) 1.15f else 1f)
                )
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                
                val centerX = canvasWidth / 2f
                val baseRadius = canvasWidth * 0.8f
                val centerY = baseRadius + canvasHeight * 0.05f
                
                val bottomPath = Path().apply {
                    moveTo(0f, canvasHeight)
                    lineTo(canvasWidth, canvasHeight)
                    lineTo(canvasWidth, 0f)
                    arcTo(
                        Rect(centerX - baseRadius, centerY - baseRadius, centerX + baseRadius, centerY + baseRadius),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = -180f,
                        forceMoveTo = false
                    )
                    lineTo(0f, canvasHeight)
                    close()
                }
                
                val baseColor = if (bottomActive) Color.White else inactiveColor
                drawPath(
                    path = bottomPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            baseColor.copy(alpha = 0.8f),
                            baseColor.copy(alpha = 0.5f),
                            baseColor.copy(alpha = 0.0f)
                        ),
                        startY = 0f,
                        endY = canvasHeight
                    )
                )
            }
            
            Text(
                text = "松开结束",
                color = if (bottomActive) Color.DarkGray else Color.Gray,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun AudioSpectrumAnimation(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    amplitude: Float = 0f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spectrum")
    
    val barCount = 9
    
    val anim1 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "anim1"
    )
    
    val anim2 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(180, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "anim2"
    )
    
    val anim3 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(120, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "anim3"
    )
    
    val anim4 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "anim4"
    )
    
    val anim5 by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(130, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "anim5"
    )
    
    val anim6 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(160, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "anim6"
    )
    
    val anim7 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "anim7"
    )
    
    val anim8 by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(220, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "anim8"
    )
    
    val anim9 by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(250, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "anim9"
    )
    
    val anims = listOf(anim1, anim2, anim3, anim4, anim5, anim6, anim7, anim8, anim9)
    
    val colors = listOf(
        Color(0xFFFF6B6B),
        Color(0xFFFF8E72),
        Color(0xFFFF9F43),
        Color(0xFFFFB86C),
        Color(0xFFFFEAA7),
        Color(0xFF55EFC4),
        Color(0xFF74D7AE),
        Color(0xFF54A0FF),
        Color(0xFF5F27CD),
    )
    
    val amplitudeValue = amplitude.coerceIn(0f, 1f)
    
    Canvas(modifier = modifier) {
        val barWidth = size.width / (barCount * 2.2f)
        val spacing = barWidth * 0.6f
        val maxHeight = size.height
        
        anims.forEachIndexed { index, animValue ->
            val baseHeight = animValue
            
            val barHeight = maxHeight * (
                if (isActive) {
                    (baseHeight * 0.4f + amplitudeValue * 0.6f).coerceIn(0.1f, 0.95f)
                } else {
                    0.1f
                }
            )
            
            val totalWidth = barCount * barWidth + (barCount - 1) * spacing
            val startX = (size.width - totalWidth) / 2f
            val x = startX + index * (barWidth + spacing)
            val y = (maxHeight - barHeight) / 2f
            
            val barAlpha = if (isActive) {
                0.5f + amplitudeValue * 0.5f
            } else {
                0.3f
            }
            
            drawRoundRect(
                color = colors[index % colors.size].copy(alpha = barAlpha),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}