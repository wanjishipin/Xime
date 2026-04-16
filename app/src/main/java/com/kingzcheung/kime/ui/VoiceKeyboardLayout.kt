package com.kingzcheung.kime.ui

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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt
import kotlin.math.atan2
import kotlin.math.PI

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
        
        // 中间：左右弯曲圆角矩形按钮（白色背景）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                
                val centerX = canvasWidth / 2f
                val gap = canvasWidth * 0.08f
                
                // 弧形弯曲程度（和底部按钮一致）
                val arcHeight = canvasHeight * 0.5f
                
                // 中间半圆半径
                val cornerRadius = canvasHeight / 2f
                
                // 左按钮终点 X（半圆圆心位置）
                val leftEndX = centerX - gap / 2
                val leftWidth = leftEndX
                
                // 左按钮路径（逆时针）：
                // 左上角 → 上边弧 → 右上角半圆 → 右边 → 右下角半圆 → 下边弧 → 左下角 → 左边闭合
                val leftPath = Path().apply {
                    // 起点：左上角
                    moveTo(0f, 0f)
                    
                    // 上边弧：向上凸（弯曲）
                    quadraticBezierTo(
                        leftWidth / 2, -arcHeight,
                        leftEndX - cornerRadius, 0f
                    )
                    
                    // 右上角半圆（向左弯曲，逆时针从右到左）
                    arcTo(
                        Rect(
                            left = leftEndX - cornerRadius * 2,
                            top = 0f,
                            right = leftEndX,
                            bottom = cornerRadius * 2
                        ),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = -180f,
                        forceMoveTo = false
                    )
                    
                    // 右边：从半圆底部向下到底部
                    lineTo(leftEndX - cornerRadius * 2, canvasHeight - cornerRadius)
                    
                    // 右下角半圆（向左弯曲，顺时针从右到左）
                    arcTo(
                        Rect(
                            left = leftEndX - cornerRadius * 2,
                            top = canvasHeight - cornerRadius * 2,
                            right = leftEndX,
                            bottom = canvasHeight
                        ),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = 180f,
                        forceMoveTo = false
                    )
                    
                    // 下边弧：向上凸（弯曲）
                    quadraticBezierTo(
                        leftWidth / 2, canvasHeight + arcHeight,
                        0f, canvasHeight
                    )
                    
                    // 左边向上闭合
                    lineTo(0f, 0f)
                    
                    close()
                }
                
                drawPath(path = leftPath, color = Color.White)
                
                // 右按钮起点 X（半圆圆心位置）
                val rightStartX = centerX + gap / 2
                val rightWidth = canvasWidth - rightStartX
                
                // 右按钮路径（顺时针）：
                // 右上角 → 上边弧 → 左上角半圆 → 左边 → 左下角半圆 → 下边弧 → 右下角 → 右边闭合
                val rightPath = Path().apply {
                    // 起点：右上角
                    moveTo(canvasWidth, 0f)
                    
                    // 上边弧：向上凸（弯曲）
                    quadraticBezierTo(
                        rightStartX + rightWidth / 2, -arcHeight,
                        rightStartX + cornerRadius, 0f
                    )
                    
                    // 左上角半圆（向右弯曲，顺时针从左到右）
                    arcTo(
                        Rect(
                            left = rightStartX,
                            top = 0f,
                            right = rightStartX + cornerRadius * 2,
                            bottom = cornerRadius * 2
                        ),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = 180f,
                        forceMoveTo = false
                    )
                    
                    // 左边：从半圆底部向下到底部
                    lineTo(rightStartX + cornerRadius * 2, canvasHeight - cornerRadius)
                    
                    // 左下角半圆（向右弯曲，逆时针从左到右）
                    arcTo(
                        Rect(
                            left = rightStartX,
                            top = canvasHeight - cornerRadius * 2,
                            right = rightStartX + cornerRadius * 2,
                            bottom = canvasHeight
                        ),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = -180f,
                        forceMoveTo = false
                    )
                    
                    // 下边弧：向上凸（弯曲）
                    quadraticBezierTo(
                        rightStartX + rightWidth / 2, canvasHeight + arcHeight,
                        canvasWidth, canvasHeight
                    )
                    
                    // 右边向上闭合
                    lineTo(canvasWidth, 0f)
                    
                    close()
                }
                
                drawPath(path = rightPath, color = Color.White)
            }
        }
        
        // 底部：向上凸的半圆弧按压区域（拱形）
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
                
                // 用更大的半径 + 圆心在 Canvas 下方，产生轻微向上凸的弧
                val arcRadius = canvasWidth * 1.2f
                val centerX = canvasWidth / 2f
                val centerY = arcRadius + canvasHeight * 0.15f
                
                val path = Path().apply {
                    // 从左下角开始
                    moveTo(0f, canvasHeight)
                    
                    // 底边到右下角
                    lineTo(canvasWidth, canvasHeight)
                    
                    // 右边向上到顶部
                    lineTo(canvasWidth, 0f)
                    
                    // 绘制轻微向上凸的弧（拱形）
                    arcTo(
                        rect = Rect(
                            left = centerX - arcRadius,
                            top = centerY - arcRadius,
                            right = centerX + arcRadius,
                            bottom = centerY + arcRadius
                        ),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = -180f,
                        forceMoveTo = false
                    )
                    
                    // 左边向下闭合
                    lineTo(0f, canvasHeight)
                    
                    close()
                }
                
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f),
                            keyBackgroundColor
                        )
                    )
                )
            }
            
            Text(
                text = "松开结束",
                color = Color.Gray,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}