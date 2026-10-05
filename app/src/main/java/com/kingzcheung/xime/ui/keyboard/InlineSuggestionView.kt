package com.kingzcheung.xime.ui.keyboard

import android.os.Build
import android.util.Size
import android.view.inputmethod.InlineSuggestion
import android.widget.inline.InlineContentView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kingzcheung.xime.service.InlineSuggestionViews

@Composable
fun InlineSuggestionView(
    suggestion: Any?,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    val realSuggestion = suggestion as? InlineSuggestion ?: return
    val context = LocalContext.current
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val targetWidthPx = with(density) { maxWidth.toPx().toInt() }
        val targetHeightPx = with(density) { maxHeight.toPx().toInt() }

        // InlineSuggestion#inflate 对同一对象只能调用一次（平台限制），
        // 已 inflate 的结果由进程级缓存保存，避免重组/进出组合时重复 inflate 崩溃。
        LaunchedEffect(realSuggestion) {
            InlineSuggestionViews.inflate(
                realSuggestion,
                context,
                Size(targetWidthPx, targetHeightPx),
            )
        }

        val view = InlineSuggestionViews.views[realSuggestion]
        if (view != null) {
            key(System.identityHashCode(realSuggestion)) {
                AndroidView(
                    factory = { view },
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

@Composable
fun InlineSuggestionDivider(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFDADCE0),
) {
    Box(
        modifier = modifier
            .width(1.dp)
            .fillMaxHeight()
            .padding(vertical = 6.dp)
            .background(color),
    )
}
