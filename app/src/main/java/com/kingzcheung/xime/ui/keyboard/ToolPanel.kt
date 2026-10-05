package com.kingzcheung.xime.ui.keyboard

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.widget.doAfterTextChanged
import com.kingzcheung.xime.plugin.core.config.UiNode
import com.kingzcheung.xime.plugin.core.config.UiNodeType
import com.kingzcheung.xime.service.ToolPanelEditTextHolder

// ---- 面板高度构成（toolPanelHeightDp 与 service 容器撑高共用，改一处必须同步核对） ----
/** CandidateBarOverlayPanel 垂直装饰总高：外层 padding 6x2 + 标题行 36 + 内容上距 4 + 卡片 padding 10x2。 */
internal const val TOOL_PANEL_CHROME_HEIGHT_DP = 72
/** 主输入条高度（单行）。 */
internal const val TOOL_PANEL_INPUT_HEIGHT_DP = 48
/** 控件行高度（48dp 触控目标）+ 行距 8。 */
internal const val TOOL_PANEL_CONTROLS_EXTRA_DP = 56
/** 内容自适应的上限，防异常节点撑爆小屏。 */
internal const val TOOL_PANEL_MAX_HEIGHT_DP = 240

/** 面板总高贴合内容：装饰 + 输入条 + 可选控件行（loading 指示在标题栏，不影响高度）。 */
internal fun toolPanelHeightDp(hasControls: Boolean): Int =
    (
        TOOL_PANEL_CHROME_HEIGHT_DP + TOOL_PANEL_INPUT_HEIGHT_DP +
            (if (hasControls) TOOL_PANEL_CONTROLS_EXTRA_DP else 0)
        ).coerceAtMost(TOOL_PANEL_MAX_HEIGHT_DP)

/** direct 面板控件行 v1 渲染白名单（其余类型忽略，协议文档已注明）。 */
internal val DIRECT_PANEL_NODE_TYPES =
    setOf(UiNodeType.TEXT, UiNodeType.SELECT, UiNodeType.BUTTON, UiNodeType.SECTION, UiNodeType.DIVIDER)

internal fun filterDirectPanelNodes(nodes: List<UiNode>): List<UiNode> =
    nodes.filter { it.type in DIRECT_PANEL_NODE_TYPES }

/** options 约定："label|value"（显示文本|回传值），无 | 时显示与值相同。 */
internal fun parseSelectOption(option: String): Pair<String, String> {
    val idx = option.lastIndexOf('|')
    return if (idx > 0) option.substring(0, idx) to option.substring(idx + 1) else option to option
}

/**
 * AI 工具插件输入面板（候选栏上方）。
 *
 * 仅承载输入与生成触发：单行输入条（预填上下文，键盘按键路由可注入）+
 * 可选声明式控件行（getPanelState().ui，如翻译的源/目标语言选择与互换键）。
 * loading 指示显示在标题栏（不占内容高度，面板高度不随 loading 变化）；
 * 面板总高贴合内容（toolPanelHeightDp），不再固定占高。
 * 生成结果不显示在此面板，多条结果由 passive 面板（InfoPanel 内 items 点选上屏）承载；
 * direct 单结果由宿主在生成结束后直接上屏。
 */
@Composable
fun ToolPanel(
    title: String,
    isFocused: Boolean,
    isLoading: Boolean = false,
    initialText: String = "",
    controls: List<UiNode>? = null,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    cardBgColor: Color,
    onClose: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onFieldInput: (key: String, value: String) -> Unit = { _, _ -> },
    onPanelAction: (actionId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val closeButtonBg = lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primary,
        0.35f
    )
    val visibleControls = remember(controls) { filterDirectPanelNodes(controls ?: emptyList()) }

    CandidateBarOverlayPanel(
        heightDp = toolPanelHeightDp(visibleControls.isNotEmpty()),
        backgroundColor = backgroundColor,
        cardBgColor = cardBgColor,
        closeButtonBg = closeButtonBg,
        closeButtonColor = accentColor,
        title = title,
        titleColor = textColor,
        titleLoading = isLoading,
        modifier = modifier,
        onCloseClick = onClose,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ToolPanelMainInput(
                initialText = initialText,
                isFocused = isFocused,
                onFocusChange = onFocusChange,
            )
            if (visibleControls.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ToolPanelControlsRow(visibleControls, onFieldInput, onPanelAction, onFocusChange)
            }
        }
    }
}

/** 面板输入框失焦/聚焦统一处理：聚焦记入 holder（按键路由目标），失焦 post 确认无其他字段接管再上报。 */
private fun handlePanelFieldFocus(
    et: android.widget.EditText,
    hasFocus: Boolean,
    onFocusChange: (Boolean) -> Unit,
) {
    if (hasFocus) {
        ToolPanelEditTextHolder.editText = et
        onFocusChange(true)
    } else {
        et.post {
            if (!et.isFocused && ToolPanelEditTextHolder.editText === et) {
                ToolPanelEditTextHolder.editText = null
                onFocusChange(false)
            }
        }
    }
}

/** 圆角输入底（M3 surfaceVariant 语义色）。 */
private fun fieldBackground(context: android.content.Context, color: Color, radiusDp: Float = 24f): GradientDrawable {
    val d = context.resources.displayMetrics.density
    return GradientDrawable().apply {
        setColor(color.toArgb())
        cornerRadius = radiusDp * d
    }
}

@Composable
private fun ToolPanelMainInput(
    initialText: String,
    isFocused: Boolean,
    onFocusChange: (Boolean) -> Unit,
) {
    val fieldBg = MaterialTheme.colorScheme.surfaceVariant
    val fieldText = MaterialTheme.colorScheme.onSurfaceVariant
    AndroidView(
        factory = { context ->
            val d = context.resources.displayMetrics.density
            android.widget.EditText(context).apply {
                background = fieldBackground(context, fieldBg)
                setTextColor(fieldText.toArgb())
                setHintTextColor(fieldText.copy(alpha = 0.4f).toArgb())
                hint = "输入上下文或指令"
                textSize = 14f
                isSingleLine = true
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)

                setImeActionLabel("生成", EditorInfo.IME_ACTION_DONE)
                imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or
                    EditorInfo.IME_ACTION_DONE

                doAfterTextChanged {
                    if (!ToolPanelEditTextHolder.applyingProgrammatically) {
                        ToolPanelEditTextHolder.userEdited = true
                    }
                }
                onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    handlePanelFieldFocus(v as android.widget.EditText, hasFocus, onFocusChange)
                }
                setOnClickListener { if (!isFocused) requestFocus() }
                ToolPanelEditTextHolder.main = this
                if (isFocused) {
                    post { requestFocus() }
                }
            }
        },
        update = { editText ->
            // 回填防覆盖：用户编辑过（userEdited）后宿主不再写输入框；
            // 未编辑时仅在目标内容与当前不同才写入（插件轮询回传相同 inputText 不重置光标）
            if (!ToolPanelEditTextHolder.userEdited) {
                val want = initialText.replace('\n', ' ')
                if (editText.text.toString() != want) {
                    ToolPanelEditTextHolder.applyingProgrammatically = true
                    editText.setText(want)
                    editText.setSelection(want.length)
                    ToolPanelEditTextHolder.applyingProgrammatically = false
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(TOOL_PANEL_INPUT_HEIGHT_DP.dp),
    )
}

@Composable
private fun ToolPanelControlsRow(
    nodes: List<UiNode>,
    onFieldInput: (String, String) -> Unit,
    onPanelAction: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    // 同时只展开一个下拉：展开态提升到行级，点另一个选择框时收起前一个
    var expandedSelectKey by remember { mutableStateOf<String?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        nodes.forEach { node ->
            when (node.type) {
                UiNodeType.SECTION -> Text(
                    text = node.label ?: "",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                UiNodeType.DIVIDER -> Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                )
                UiNodeType.TEXT ->
                    if (node.key.isNullOrBlank()) {
                        Text(
                            text = node.value ?: node.label ?: "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        PanelTextField(node, onFieldInput, onFocusChange)
                    }
                UiNodeType.SELECT -> PanelSelectField(
                    node = node,
                    expanded = !node.key.isNullOrBlank() && expandedSelectKey == node.key,
                    onExpandChange = { expandedSelectKey = if (it) node.key else null },
                    onFieldInput = onFieldInput,
                )
                UiNodeType.BUTTON -> {
                    val actionId = node.key
                    if (!actionId.isNullOrBlank()) {
                        FilledTonalButton(
                            onClick = { onPanelAction(actionId) },
                            modifier = Modifier.height(44.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            Text(
                                text = node.label ?: actionId,
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
                else -> {}
            }
        }
    }
}

/** 控件行文本输入框：初始 value 来自节点，用户编辑后以用户输入为准（宿主不再回写）。 */
@Composable
private fun PanelTextField(
    node: UiNode,
    onFieldInput: (String, String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    val key = node.key ?: return
    val fieldBg = MaterialTheme.colorScheme.surfaceVariant
    val fieldText = MaterialTheme.colorScheme.onSurfaceVariant
    var userEdited by remember(key) { mutableStateOf(false) }
    AndroidView(
        factory = { context ->
            val d = context.resources.displayMetrics.density
            android.widget.EditText(context).apply {
                background = fieldBackground(context, fieldBg, 20f)
                setTextColor(fieldText.toArgb())
                setHintTextColor(fieldText.copy(alpha = 0.4f).toArgb())
                hint = node.placeholder ?: node.label ?: ""
                textSize = 13f
                isSingleLine = true
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                doAfterTextChanged {
                    userEdited = true
                    onFieldInput(key, text?.toString() ?: "")
                }
                onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    handlePanelFieldFocus(v as android.widget.EditText, hasFocus, onFocusChange)
                }
            }
        },
        update = { editText ->
            val want = (node.value ?: "").replace('\n', ' ')
            if (!userEdited && editText.text.toString() != want) {
                editText.setText(want)
                editText.setSelection(want.length)
            }
        },
        modifier = Modifier
            .width(120.dp)
            .height(48.dp),
    )
}

/**
 * 控件行下拉选择：静态 options，"label|value" 约定；ui 重拉后以插件回传 value 为准。
 *
 * 用 focusable = false 的 Popup 而非 DropdownMenu：DropdownMenu 内部是可聚焦弹窗，
 * 在输入法窗口里会抢走窗口焦点 → 系统判定编辑框失焦 → 整个输入法被收起。
 * 非聚焦弹窗不动焦点（输入法保持），代价是点击弹层外部不会自动收起——
 * 收起途径：再点一次选择框、选中一项、或面板关闭。
 */
@Composable
private fun PanelSelectField(
    node: UiNode,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onFieldInput: (String, String) -> Unit,
) {
    val key = node.key ?: return
    if (node.options.isEmpty()) return
    var selected by remember(key) { mutableStateOf(node.value ?: node.options.first()) }
    LaunchedEffect(node) { node.value?.let { selected = it } }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onExpandChange(!expanded) }
                .padding(horizontal = 12.dp)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = selectLabel(selected, node.options),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = node.label ?: "选择",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(0, 8),
                onDismissRequest = { onExpandChange(false) },
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .shadow(4.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .widthIn(min = 140.dp)
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    node.options.forEach { option ->
                        val (label, value) = parseSelectOption(option)
                        Row(
                            modifier = Modifier
                                .clickable {
                                    onExpandChange(false)
                                    selected = value
                                    onFieldInput(key, value)
                                }
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                maxLines = 1,
                                color = if (value == selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (value == selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun selectLabel(value: String, options: List<String>): String =
    options.firstOrNull { parseSelectOption(it).second == value }
        ?.let { parseSelectOption(it).first }
        ?: value
