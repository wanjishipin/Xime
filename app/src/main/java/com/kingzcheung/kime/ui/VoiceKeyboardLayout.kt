package com.kingzcheung.kime.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

@Composable
fun VoiceKeyboardLayout(
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    modifier: Modifier = Modifier,
    onUndo: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(specialKeyBackgroundColor.copy(alpha = 0.3f)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 顶部：语音可视化区域
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
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "语音输入",
                    tint = keyTextColor,
                    modifier = Modifier.size(64.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "正在聆听...",
                    color = keyTextColor.copy(alpha = 0.8f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(5) { index ->
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height((20 + index * 8).dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(keyTextColor.copy(alpha = 0.6f))
                        )
                    }
                }
            }
        }
        
// 中间：左右两个等边三角形按钮（中间角是大圆角）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
        ) {
            // 左按钮：等边三角形，左侧垂直边贴合左边缘，中间角大圆角
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val canvasHeight = size.height
                    val sideLength = canvasHeight
                    
                    // 等边三角形顶点
                    val Ax = 0f
                    val Ay = 0f
                    val Bx = 0f
                    val By = sideLength
                    val Cx = sideLength * sqrt(3f) / 2f
                    val Cy = sideLength / 2f
                    
                    // 圆角半径
                    val cornerRadius = sideLength * 0.25f
                    
                    // 计算从A到C方向偏移cornerRadius的点
                    val ACx = Ax + (Cx - Ax) * cornerRadius / sideLength
                    val ACy = Ay + (Cy - Ay) * cornerRadius / sideLength
                    
                    // 计算从B到C方向偏移cornerRadius的点
                    val BCx = Bx + (Cx - Bx) * cornerRadius / sideLength
                    val BCy = By + (Cy - By) * cornerRadius / sideLength
                    
                    val path = Path().apply {
                        moveTo(Ax, Ay)
                        lineTo(ACx, ACy)
                        // 用二次贝塞尔曲线画圆角
                        quadraticBezierTo(Cx, Cy, BCx, BCy)
                        lineTo(Bx, By)
                        close()
                    }
                    drawPath(path = path, color = Color.White)
                }
            }
            
            // 右按钮：等边三角形，右侧垂直边贴合右边缘，中间角大圆角
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
                    val sideLength = canvasHeight
                    
                    // 等边三角形顶点
                    val Ax = canvasWidth
                    val Ay = 0f
                    val Bx = canvasWidth
                    val By = sideLength
                    val Cx = canvasWidth - sideLength * sqrt(3f) / 2f
                    val Cy = sideLength / 2f
                    
                    // 圆角半径
                    val cornerRadius = sideLength * 0.25f
                    
                    // 计算偏移点
                    val ACx = Ax + (Cx - Ax) * cornerRadius / sideLength
                    val ACy = Ay + (Cy - Ay) * cornerRadius / sideLength
                    
                    val BCx = Bx + (Cx - Bx) * cornerRadius / sideLength
                    val BCy = By + (Cy - By) * cornerRadius / sideLength
                    
                    val path = Path().apply {
                        moveTo(Ax, Ay)
                        lineTo(ACx, ACy)
                        quadraticBezierTo(Cx, Cy, BCx, BCy)
                        lineTo(Bx, By)
                        close()
                    }
                    drawPath(path = path, color = Color.White)
                }
            }
        }
        
        // 底部按钮 Canvas（独立）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                
                val centerX = canvasWidth / 2f
                val baseRadius = canvasWidth * 1.2f
                val centerY = baseRadius + canvasHeight * 0.15f
                
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
                drawPath(
                    path = bottomPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.95f), Color(0xFFE0E0E0))
                    )
                )
            }
            
            Text(
                text = "松开结束",
                color = Color.Gray,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 8.dp)
            )
        }
    }
}