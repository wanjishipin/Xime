package com.kingzcheung.xime.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.SurfaceControl
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kingzcheung.xime.R
import java.util.concurrent.Executor

/**
 * 进程内共享的 InlineSuggestion 视图缓存。
 *
 * [InlineSuggestion.inflate] 对同一个对象实例只能调用一次，重复调用会抛出
 * `IllegalStateException("Already called #inflate()")`。这里按对象缓存已
 * inflate 的 [InlineContentView]，跨重组/进出组合复用，并在建议更新或清除时
 * 释放旧视图对应的 surface。
 */
internal object InlineSuggestionViews {
    val views = mutableStateMapOf<InlineSuggestion, InlineContentView?>()

    private val inflateExecutor: Executor =
        Executor { command -> Handler(Looper.getMainLooper()).post(command) }

    @RequiresApi(Build.VERSION_CODES.R)
    fun inflate(suggestion: InlineSuggestion, context: Context, size: Size) {
        if (views[suggestion] != null) return
        suggestion.inflate(context, size, inflateExecutor) { contentView ->
            views[suggestion] = contentView
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun retain(keep: Collection<InlineSuggestion>) {
        val keepSet = keep.toSet()
        views.keys.filter { it !in keepSet }.forEach { release(it) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun releaseAll() {
        views.keys.toList().forEach { release(it) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun release(suggestion: InlineSuggestion) {
        views.remove(suggestion)?.surfaceControl?.let { sc ->
            SurfaceControl.Transaction().reparent(sc, null).apply()
        }
    }
}

class InlineSuggestionManager(private val context: Context) {

    var suggestions by mutableStateOf<List<InlineSuggestion>>(emptyList())
        private set

    var isAvailable: Boolean = false
        private set

    var candidateTextColorArgb: Int = Color.BLACK
    var labelTextColorArgb: Int = Color.GRAY
    var isDarkTheme: Boolean = false

    @RequiresApi(Build.VERSION_CODES.R)
    fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        return try {
            val textColor = candidateTextColorArgb
            val altTextColor = labelTextColorArgb
            val chipDrawable = if (isDarkTheme) {
                R.drawable.bg_inline_suggestion_dark
            } else {
                R.drawable.bg_inline_suggestion_light
            }
            val chipBg = Icon.createWithResource(context, chipDrawable).apply {
                setTint(textColor)
            }
            val density = context.resources.displayMetrics.density
            val style = InlineSuggestionUi.newStyleBuilder()
                .setSingleIconChipStyle(
                    ViewStyle.Builder()
                        .setBackgroundColor(Color.TRANSPARENT)
                        .setPadding(0, 0, 0, 0)
                        .build()
                )
                .setChipStyle(
                    ViewStyle.Builder()
                        .setBackground(chipBg)
                        .setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                        .build()
                )
                .setTitleStyle(
                    TextViewStyle.Builder()
                        .setTextColor(textColor)
                        .setTextSize(15f)
                        .build()
                )
                .setSubtitleStyle(
                    TextViewStyle.Builder()
                        .setTextColor(altTextColor)
                        .setTextSize(12f)
                        .build()
                )
                .setStartIconStyle(
                    ImageViewStyle.Builder()
                        .setTintList(ColorStateList.valueOf(altTextColor))
                        .build()
                )
                .setEndIconStyle(
                    ImageViewStyle.Builder()
                        .setTintList(ColorStateList.valueOf(altTextColor))
                        .build()
                )
                .build()
            val styleBundle = UiVersions.newStylesBuilder()
                .addStyle(style)
                .build()
            val spec = InlinePresentationSpec.Builder(
                Size(0, 0), Size(800, 400)
            ).setStyle(styleBundle).build()
            InlineSuggestionsRequest.Builder(listOf(spec))
                .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
                .build()
        } catch (e: Throwable) {
            Log.w("InlineSuggestionManager", "onCreateInlineSuggestionsRequest failed", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        isAvailable = true
        val newSuggestions = response.inlineSuggestions
        // 空响应表示宿主撤销了建议（如用户开始输入），需清除旧建议
        if (newSuggestions.isEmpty() && suggestions.isEmpty()) {
            return true
        }
        suggestions = newSuggestions
        InlineSuggestionViews.retain(newSuggestions)
        return true
    }

    fun clear() {
        suggestions = emptyList()
        isAvailable = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            InlineSuggestionViews.releaseAll()
        }
    }
}
