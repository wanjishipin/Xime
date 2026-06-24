package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.service.PredictionManager
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.keyboard.KeyboardRoute
import com.kingzcheung.xime.keyboard.ToolbarAction
import com.kingzcheung.xime.settings.SettingsPreferences

@Immutable
data class CandidateBarVisuals(
    val backgroundColor: Color,
    val textColor: Color,
    val dividerColor: Color,
    val accentColor: Color = Color(0xFF1A73E8),
    val isDarkTheme: Boolean = false,
)

data class CandidateBarCallbacks(
    val onCandidateSelect: (Int) -> Unit,
    val onLogoClick: (() -> Unit)? = null,
    val onBack: (() -> Unit)? = null,
    val onHideKeyboard: (() -> Unit)? = null,
    val onShowMoreCandidates: (() -> Unit)? = null,
    val onClearAssociation: (() -> Unit)? = null,
    val onInputTextClick: (() -> Unit)? = null,
    val onAssociationSelect: ((Int) -> Unit)? = null
)

@Composable
fun CandidateBar(
    state: CandidateBarState,
    currentRoute: KeyboardRoute = KeyboardRoute.Keyboard,
    toolbarActions: List<ToolbarAction> = emptyList(),
    visuals: CandidateBarVisuals,
    callbacks: CandidateBarCallbacks,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier,
    isFloatingMode: Boolean = false,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val horizontalPadding = if (isLandscape) 50.dp else 8.dp
    val context = LocalContext.current
    val showComments = SettingsPreferences.showCandidateComments(context)

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val itemPaddingPx = with(density) { 8.dp.toPx() }
    val spacingPx = with(density) { 4.dp.toPx() }

    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val rowPaddingPx = with(density) { 16.dp.toPx() }
    val rightSidePx = with(density) {
        val moreBtn = if (callbacks.onShowMoreCandidates != null) 38.dp.toPx() else 0f
        val clearBtn = if (callbacks.onClearAssociation != null) 38.dp.toPx() else 0f
        val hideBtn = if (callbacks.onHideKeyboard != null) 28.dp.toPx() else 0f
        rowPaddingPx + maxOf(moreBtn, clearBtn) + hideBtn + 8.dp.toPx()
    }

    val displayCandidates: List<String>
    val displayAssociation: List<String>
    val hasAnyMore: Boolean
    val showInputTextRow: Boolean
    val showLeftIcon: Boolean

    when (val s = state) {
        is CandidateBarState.Idle -> {
            displayCandidates = emptyList()
            displayAssociation = emptyList()
            hasAnyMore = false
            showLeftIcon = true
        }
        is CandidateBarState.ChineseCandidates -> {
            val taken = s.candidates.take(20)
            displayCandidates = taken
            hasAnyMore = s.hasMore
            showLeftIcon = false
            displayAssociation = remember(s.associationCandidates, taken, s.inputText, textMeasurer) {
                if (taken.isEmpty()) {
                    s.associationCandidates.take(PredictionManager.MAX_ASSOCIATION_COUNT)
                } else {
                    val measureText = { text: String ->
                        textMeasurer.measure(
                            text = AnnotatedString(text),
                            style = TextStyle(fontSize = 19.sp)
                        ).size.width.toFloat()
                    }
                    val leftSidePx = with(density) { rowPaddingPx + 32.dp.toPx() }
                    val lazyRowWidthPx = screenWidthPx - leftSidePx - rightSidePx
                    val regularWidthPx = taken.sumOf { c ->
                        measureText(c).toDouble() + itemPaddingPx
                    }.toFloat()
                    val dividerWidthPx = with(density) { 9.dp.toPx() }
                    val availablePx = lazyRowWidthPx - regularWidthPx - dividerWidthPx

                    var usedPx = 0f
                    val result = mutableListOf<String>()
                    for (c in s.associationCandidates) {
                        val w =
                            measureText(c) + itemPaddingPx + (if (result.isEmpty()) 0f else spacingPx)
                        if (usedPx + w <= availablePx) {
                            usedPx += w
                            result.add(c)
                        } else break
                    }
                    result
                }
            }
        }
        is CandidateBarState.AssociationOnly -> {
            displayCandidates = emptyList()
            displayAssociation = s.candidates.take(PredictionManager.MAX_ASSOCIATION_COUNT)
            hasAnyMore = s.hasMore
            showLeftIcon = false
        }
        is CandidateBarState.EnglishCandidates -> {
            displayCandidates = s.candidates.take(20)
            displayAssociation = emptyList()
            hasAnyMore = false
            showLeftIcon = false
        }
        is CandidateBarState.ClipboardDisplay -> {
            displayCandidates = s.candidates.take(20)
            displayAssociation = emptyList()
            hasAnyMore = false
            showLeftIcon = true
        }
        is CandidateBarState.Calculator -> {
            displayCandidates = s.candidates.take(20)
            displayAssociation = emptyList()
            hasAnyMore = false
            showLeftIcon = false
        }
    }
    showInputTextRow = currentRoute !is KeyboardRoute.Clipboard

    val candidateListState = rememberLazyListState()
    LaunchedEffect(displayCandidates) {
        candidateListState.scrollToItem(0)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(visuals.backgroundColor)
            .padding(horizontal = horizontalPadding),
        verticalArrangement = Arrangement.Center
    ) {
        val displayText = (state as? CandidateBarState.ChineseCandidates)?.preeditText
            ?: (state as? CandidateBarState.ChineseCandidates)?.inputText ?: ""
        val showInputText = showInputTextRow && displayText.isNotEmpty()

        if (showInputText) {
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
                    text = displayText,
                    color = visuals.textColor.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isInputTextPressed && callbacks.onInputTextClick != null)
                                (if (visuals.isDarkTheme) Color.White.copy(alpha = 0.15f) else Color.Black.copy(
                                    alpha = 0.1f
                                ))
                            else
                                Color.Transparent
                        )
                        .padding(horizontal = 0.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showLeftIcon) {
                when (state) {
                    is CandidateBarState.Idle -> {
                        if (currentRoute is KeyboardRoute.SchemaList && callbacks.onBack != null) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (visuals.isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                                    .clickable { callbacks.onBack() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = "返回菜单",
                                    tint = visuals.accentColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (visuals.isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                                    .clickable { callbacks.onLogoClick?.invoke() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = if (visuals.isDarkTheme) R.drawable.logo_dark else R.drawable.logo),
                                    contentDescription = "曦码 Logo",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    is CandidateBarState.ClipboardDisplay -> {
                        Row(
                            modifier = Modifier.padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "剪切板",
                                tint = visuals.accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    else -> {}
                }
            }

            LazyRow(
                modifier = Modifier.weight(1f),
                state = candidateListState,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(displayCandidates, key = { index, _ -> index }) { index, candidate ->
                    CandidateItem(
                        text = candidate,
                        index = index,
                        onClick = { callbacks.onCandidateSelect(index) },
                        textColor = visuals.textColor,
                        comment = if (showComments) {
                            when (val s = state) {
                                is CandidateBarState.ChineseCandidates -> s.comments.getOrElse(index) { "" }
                                is CandidateBarState.EnglishCandidates -> s.comments.getOrElse(index) { "" }
                                else -> ""
                            }
                        } else "",
                        isSelected = index == 0,
                        accentColor = visuals.accentColor
                    )
                }

                if (displayAssociation.isNotEmpty()) {
                    item(key = "divider") {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(20.dp)
                                .background(visuals.dividerColor.copy(alpha = 0.5f))
                                .padding(horizontal = 4.dp)
                        )
                    }

                    itemsIndexed(displayAssociation, key = { index, _ -> "assoc-$index" }) { index, candidate ->
                        CandidateItem(
                            text = candidate,
                            index = -1,
                            onClick = { callbacks.onAssociationSelect?.invoke(index) },
                            textColor = visuals.textColor,
                            comment = "",
                            isSelected = false,
                            accentColor = visuals.accentColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            when {
                state is CandidateBarState.Idle -> {
                    if (toolbarActions.isNotEmpty()) {
                        toolbarActions.forEach { action ->
                            val interactionSource = remember { MutableInteractionSource() }
                            val isPressed by interactionSource.collectIsPressedAsState()
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 5.dp)
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isPressed) (if (visuals.isDarkTheme) Color.White.copy(
                                            alpha = 0.15f
                                        ) else Color.Black.copy(alpha = 0.1f))
                                        else (if (visuals.isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
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
                                    tint = if (isPressed) visuals.textColor.copy(alpha = 0.6f) else if (visuals.isDarkTheme) visuals.textColor else visuals.textColor.copy(alpha = 0.65f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    if (callbacks.onHideKeyboard != null) {
                        val hideKeyboardInteractionSource = remember { MutableInteractionSource() }
                        val isHideKeyboardPressed by hideKeyboardInteractionSource.collectIsPressedAsState()

                        Spacer(modifier = Modifier.width(4.dp))

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isHideKeyboardPressed) (if (visuals.isDarkTheme) Color.White.copy(
                                        alpha = 0.15f
                                    ) else Color.Black.copy(alpha = 0.1f))
                                    else (if (visuals.isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                                )
                                .clickable(
                                    interactionSource = hideKeyboardInteractionSource,
                                    indication = null,
                                    onClick = { callbacks.onHideKeyboard() }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "收起键盘",
                                tint = if (isHideKeyboardPressed) visuals.textColor.copy(alpha = 0.6f) else visuals.textColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                currentRoute is KeyboardRoute.CandidatePage -> {
                    if (callbacks.onBack != null) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (visuals.isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                                .clickable { callbacks.onBack() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "返回键盘",
                                tint = visuals.accentColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                displayAssociation.isNotEmpty() && callbacks.onClearAssociation != null -> {
                    val clearInteractionSource = remember { MutableInteractionSource() }
                    val isClearPressed by clearInteractionSource.collectIsPressedAsState()

                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(28.dp)
                            .background(visuals.dividerColor).padding(end = 1.dp)
                    )
                    Box(
                        modifier = Modifier
                            .width(30.dp)
                            .height(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isClearPressed) (if (visuals.isDarkTheme) Color.White.copy(alpha = 0.15f) else Color.Black.copy(
                                    alpha = 0.1f
                                ))
                                else Color.Transparent
                            )
                            .clickable(
                                interactionSource = clearInteractionSource,
                                indication = null,
                                onClick = { callbacks.onClearAssociation() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "清空",
                            color = if (isClearPressed) visuals.textColor.copy(alpha = 0.6f) else visuals.textColor,
                            fontSize = 11.sp
                        )
                    }
                }
                hasAnyMore && callbacks.onShowMoreCandidates != null -> {
                    val moreInteractionSource = remember { MutableInteractionSource() }
                    val isMorePressed by moreInteractionSource.collectIsPressedAsState()

                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(28.dp)
                            .background(visuals.dividerColor).padding(end = 1.dp)
                    )
                    Box(
                        modifier = Modifier
                            .width(30.dp)
                            .height(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isMorePressed) (if (visuals.isDarkTheme) Color.White.copy(alpha = 0.15f) else Color.Black.copy(
                                    alpha = 0.1f
                                ))
                                else Color.Transparent
                            )
                            .clickable(
                                interactionSource = moreInteractionSource,
                                indication = null,
                                onClick = { callbacks.onShowMoreCandidates() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "更多",
                            color = if (isMorePressed) visuals.textColor.copy(alpha = 0.6f) else visuals.textColor,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

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
        Text(
            text = text,
            color = if (isSelected) accentColor else textColor,
            fontSize = 19.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
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
