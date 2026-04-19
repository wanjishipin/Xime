package com.kingzcheung.kime.ui

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.kime.plugin.core.api.RecognitionState
import kotlin.math.sqrt

@Composable
fun VoiceKeyboardLayout(
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    modifier: Modifier = Modifier,
    bottomActive: Boolean = false,
    leftActive: Boolean = false,
    rightActive: Boolean = false,
    pluginName: String = "",
    recognitionState: RecognitionState = RecognitionState.IDLE,
    recognizedText: String = ""
) {
    val inactiveColor = Color.Gray.copy(alpha = 0.5f)
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(specialKeyBackgroundColor.copy(alpha = 0.3f)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (pluginName.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "语音",
                            tint = keyTextColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = pluginName,
                            color = keyTextColor.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
                
                AudioSpectrumAnimation(
                    modifier = Modifier.size(120.dp, 80.dp),
                    isActive = recognitionState == RecognitionState.LISTENING || 
                               recognitionState == RecognitionState.PROCESSING
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                val statusText = when (recognitionState) {
                    RecognitionState.IDLE -> "点击开始说话"
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
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    
                    val sideLength = canvasHeight * 1.3f
                    
                    val Ax = 0f
                    val Ay = -sideLength * 0.15f
                    val Bx = 0f
                    val By = canvasHeight + sideLength * 0.15f
                    val Cx = sideLength * sqrt(3f) / 2f
                    val Cy = canvasHeight / 2f
                    
                    val cornerRadius = sideLength * 0.3f
                    
                    val AC_dist = sqrt((Cx - Ax) * (Cx - Ax) + (Cy - Ay) * (Cy - Ay))
                    val ACx = Ax + (Cx - Ax) * cornerRadius / AC_dist
                    val ACy = Ay + (Cy - Ay) * cornerRadius / AC_dist
                    
                    val BC_dist = sqrt((Cx - Bx) * (Cx - Bx) + (Cy - By) * (Cy - By))
                    val BCx = Bx + (Cx - Bx) * cornerRadius / BC_dist
                    val BCy = By + (Cy - By) * cornerRadius / BC_dist
                    
                    val path = Path().apply {
                        moveTo(Ax, Ay)
                        lineTo(ACx, ACy)
                        quadraticBezierTo(Cx, Cy, BCx, BCy)
                        lineTo(Bx, By)
                        close()
                    }
                    
                    drawPath(
                        path = path,
                        color = if (leftActive) Color.White else inactiveColor
                    )
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "撤回",
                        color = if (leftActive) Color.Black else Color.DarkGray,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    
                    val sideLength = canvasHeight * 1.3f
                    
                    val Ax = canvasWidth
                    val Ay = -sideLength * 0.15f
                    val Bx = canvasWidth
                    val By = canvasHeight + sideLength * 0.15f
                    val Cx = canvasWidth - sideLength * sqrt(3f) / 2f
                    val Cy = canvasHeight / 2f
                    
                    val cornerRadius = sideLength * 0.3f
                    
                    val AC_dist = sqrt((Cx - Ax) * (Cx - Ax) + (Cy - Ay) * (Cy - Ay))
                    val ACx = Ax + (Cx - Ax) * cornerRadius / AC_dist
                    val ACy = Ay + (Cy - Ay) * cornerRadius / AC_dist
                    
                    val BC_dist = sqrt((Cx - Bx) * (Cx - Bx) + (Cy - By) * (Cy - By))
                    val BCx = Bx + (Cx - Bx) * cornerRadius / BC_dist
                    val BCy = By + (Cy - By) * cornerRadius / BC_dist
                    
                    val path = Path().apply {
                        moveTo(Ax, Ay)
                        lineTo(ACx, ACy)
                        quadraticBezierTo(Cx, Cy, BCx, BCy)
                        lineTo(Bx, By)
                        close()
                    }
                    drawPath(
                        path = path,
                        color = if (rightActive) Color.White else inactiveColor
                    )
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 16.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = "搜索",
                        color = if (rightActive) Color.Black else Color.DarkGray,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
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
    isActive: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spectrum")
    
    val barCount = 7
    val animations = List(barCount) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.3f + index * 0.05f,
            targetValue = 0.8f - index * 0.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (isActive) 300 + index * 50 else 1000,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
    }
    
    val colors = listOf(
        Color(0xFFFF6B6B),
        Color(0xFFFF9F43),
        Color(0xFFFFEAA7),
        Color(0xFF55EFC4),
        Color(0xFF54A0FF),
        Color(0xFF5F27CD),
        Color(0xFFFF6B6B),
    )
    
    Canvas(modifier = modifier) {
        val barWidth = size.width / (barCount * 2f)
        val spacing = barWidth
        val maxHeight = size.height
        
        animations.forEachIndexed { index, animatable ->
            val animatedHeight by animatable
            val barHeight = maxHeight * (if (isActive) animatedHeight else 0.2f)
            
            val x = spacing + index * (barWidth + spacing)
            val y = (maxHeight - barHeight) / 2f
            
            drawRoundRect(
                color = colors[index].copy(alpha = if (isActive) 0.7f + animatedHeight * 0.3f else 0.3f),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}