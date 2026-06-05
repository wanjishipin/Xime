package com.kingzcheung.xime.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.ui.KeyboardRoute

/**
 * 候选栏组件
 * 显示输入编码和候选词列表
 */
@Composable
fun CandidateBar(
    candidates: List<String>,
    candidateComments: List<String> = emptyList(),
    inputText: String,
    isComposing: Boolean,
    onCandidateSelect: (Int) -> Unit,
    backgroundColor: Color,
    showClipboardHeader: Boolean = false,
    textColor: Color,
    dividerColor: Color,
    accentColor: Color = Color(0xFF1A73E8),
    isDarkTheme: Boolean = false,
    currentRoute: KeyboardRoute = KeyboardRoute.Keyboard,
    onLogoClick: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onHideKeyboard: (() -> Unit)? = null,
    onShowMoreCandidates: (() -> Unit)? = null,
    onInputTextClick: (() -> Unit)? = null,
    associationCandidates: List<String> = emptyList(),
    onAssociationSelect: ((Int) -> Unit)? = null,
    toolbarActions: List<ToolbarAction> = emptyList(),
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier
) {
    val textColor = if (isDarkTheme) Color(0xFFE8EAED) else Color(0xFF202124)
    val displayCandidates = candidates.take(20)
    val hasMoreCandidates = candidates.size >= 5
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val horizontalPadding = if (isLandscape) 50.dp else 8.dp
    val hasMoreAssociation = associationCandidates.size >= 5
    val hasAnyMore = hasMoreCandidates || hasMoreAssociation

    val density = LocalDensity.current
    val paint = remember {
        android.graphics.Paint().apply { this.textSize = with(density) { 15.sp.toPx() } }
    }
    val itemPaddingPx = with(density) { 8.dp.toPx() }
    val spacingPx = with(density) { 4.dp.toPx() }

    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val rowPaddingPx = with(density) { 16.dp.toPx() }
    val rightSidePx = with(density) {
        val moreBtn = if (hasAnyMore && onShowMoreCandidates != null) 38.dp.toPx() else 0f
        val hideBtn = if (onHideKeyboard != null) 28.dp.toPx() else 0f
        rowPaddingPx + moreBtn + hideBtn + 8.dp.toPx()
    }

    val displayAssociation =
        remember(associationCandidates, displayCandidates, isComposing, inputText) {
            if (displayCandidates.isEmpty()) {
                associationCandidates.take(5)
            } else {
                val leftSidePx = with(density) {
                    // inputText 已移到上方行，底部行只需留出 Logo 区域
                    rowPaddingPx + 32.dp.toPx()
                }
                val lazyRowWidthPx = screenWidthPx - leftSidePx - rightSidePx

                val regularWidthPx = displayCandidates.sumOf { c ->
                    (paint.measureText(c) + itemPaddingPx).toDouble()
                }.toFloat()
                val dividerWidthPx = with(density) { 9.dp.toPx() }
                val availablePx = lazyRowWidthPx - regularWidthPx - dividerWidthPx

                var usedPx = 0f
                val result = mutableListOf<String>()
                for (c in associationCandidates) {
                    val w =
                        paint.measureText(c) + itemPaddingPx + (if (result.isEmpty()) 0f else spacingPx)
                    if (usedPx + w <= availablePx) {
                        usedPx += w
                        result.add(c)
                    } else break
                }
                if (result.isEmpty() && associationCandidates.isNotEmpty()) {
                    listOf(associationCandidates.first())
                } else result
            }
        }

    Column(
        modifier = modifier
            .padding(vertical = 0.dp)
            .fillMaxWidth()
            .height(50.dp)
            .background(backgroundColor)
            .padding(horizontal = horizontalPadding)
    ) {
        // 上方行：输入编码（拼音），仅在打字时显示
        if (currentRoute !is KeyboardRoute.Clipboard && isComposing && inputText.isNotEmpty()) {
            val inputTextInteractionSource = remember { MutableInteractionSource() }
            val isInputTextPressed by inputTextInteractionSource.collectIsPressedAsState()

            Box(
                modifier = Modifier
                    .padding(vertical = 0.5.dp)
                    .fillMaxWidth()
                    .height(16.dp),
                contentAlignment = Alignment.CenterStart,

            ) {
                Text(
                    text = inputText,
                    color = textColor.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isInputTextPressed && onInputTextClick != null)
                                (if (isDarkTheme) Color.White.copy(alpha = 0.15f) else Color.Black.copy(
                                    alpha = 0.1f
                                ))
                            else
                                Color.Transparent
                        )
                        .padding(horizontal = 0.dp)
                )
            }
        }

        // 下方行：Logo + 候选词 + 操作按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f).padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
                if (!isComposing && inputText.isEmpty()) {
                    if (currentRoute is KeyboardRoute.SchemaList && onBack != null) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                                .clickable { onBack() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "返回菜单",
                                tint = accentColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                                .clickable { onLogoClick?.invoke() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = if (isDarkTheme) R.drawable.logo_dark else R.drawable.logo),
                                contentDescription = "曦码 Logo",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))
                }

                if (showClipboardHeader) {
                    Row(
                        modifier = Modifier.padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "剪切板",
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
//                Spacer(modifier = Modifier.width(2.dp))
                }

                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(displayCandidates) { index, candidate ->
                        CandidateItem(
                            text = candidate,
                            index = index,
                            onClick = { onCandidateSelect(index) },
                            textColor = textColor,
                            comment = candidateComments.getOrElse(index) { "" },
                            isSelected = index == 0,
                            accentColor = accentColor
                        )
                    }

                    if (displayAssociation.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(20.dp)
                                    .background(dividerColor.copy(alpha = 0.5f))
                                    .padding(horizontal = 4.dp)
                            )
                        }

                        itemsIndexed(displayAssociation) { index, candidate ->
                            CandidateItem(
                                text = candidate,
                                index = -1,
                                onClick = { onAssociationSelect?.invoke(index) },
                                textColor = textColor,
                                comment = ""
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (!isComposing && inputText.isEmpty() && candidates.isEmpty() && associationCandidates.isEmpty() && toolbarActions.isNotEmpty()) {
                    toolbarActions.forEach { action ->
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 5.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isPressed) (if (isDarkTheme) Color.White.copy(
                                        alpha = 0.15f
                                    ) else Color.Black.copy(alpha = 0.1f))
                                    else (if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                                )
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = action.onClick
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = action.button.icon,
                                contentDescription = action.button.label,
                                tint = if (isPressed) textColor.copy(alpha = 0.6f) else if (isDarkTheme) textColor else textColor.copy(alpha = 0.65f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (currentRoute is KeyboardRoute.CandidatePage) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                            .clickable { onBack?.invoke() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "返回键盘",
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    if (hasAnyMore && onShowMoreCandidates != null) {
                        val moreInteractionSource = remember { MutableInteractionSource() }
                        val isMorePressed by moreInteractionSource.collectIsPressedAsState()

                        Spacer(modifier = Modifier.width(4.dp))
                        // 分割线
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(28.dp)
                                .background(dividerColor).padding(end = 1.dp)
                        )
                        Box(
                            modifier = Modifier
                                .width(30.dp)
                                .height(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isMorePressed) (if (isDarkTheme) Color.White.copy(alpha = 0.15f) else Color.Black.copy(
                                        alpha = 0.1f
                                    ))
                                    else Color.Transparent
                                )
                                .clickable(
                                    interactionSource = moreInteractionSource,
                                    indication = null,
                                    onClick = { onShowMoreCandidates() }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "更多",
                                color = if (isMorePressed) textColor.copy(alpha = 0.6f) else textColor,
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (onHideKeyboard != null && (!isComposing || inputText.isEmpty())) {
                        val hideKeyboardInteractionSource = remember { MutableInteractionSource() }
                        val isHideKeyboardPressed by hideKeyboardInteractionSource.collectIsPressedAsState()

                        Spacer(modifier = Modifier.width(4.dp))

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isHideKeyboardPressed) (if (isDarkTheme) Color.White.copy(
                                        alpha = 0.15f
                                    ) else Color.Black.copy(alpha = 0.1f))
                                    else (if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                                )
                                .clickable(
                                    interactionSource = hideKeyboardInteractionSource,
                                    indication = null,
                                    onClick = { onHideKeyboard() }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "收起键盘",
                                tint = if (isHideKeyboardPressed) textColor.copy(alpha = 0.6f) else textColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
        }
    }
}
/**
 * 单个候选词项
 * 显示候选词和编码注释
 */
@Composable
fun CandidateItem(
    text: String,
    index: Int,
    onClick: () -> Unit,
    textColor: Color,
    comment: String = "",
    isSelected: Boolean = false,
    accentColor: Color = Color(0xFF1A73E8),
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (isSelected) accentColor.copy(alpha = 0.2f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 显示候选词
        Text(
            text = text,
            color = if (isSelected) accentColor else textColor,
            fontSize = 19.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
        // 显示编码注释
        if (comment.isNotEmpty()) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = comment,
                color = if (isSelected) accentColor.copy(alpha = 0.6f) else textColor.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}