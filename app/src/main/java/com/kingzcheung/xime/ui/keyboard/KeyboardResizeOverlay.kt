package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 键盘调节层（微信输入法式交互）。
 *
 * **渲染在键盘内容 Box 内部**（调用方传 `Modifier.matchParentSize()`）：
 * 遮罩、四条边手柄、三个按钮与键盘共享同一个矩形——遮罩就是键盘矩形本身，
 * 手柄锁遮罩四边、按钮在遮罩内均分，**结构上像素级对齐，不存在坐标复制误差**。
 *
 * 进入调节后：整个键盘块蒙 55% 黑半透明遮罩（按键隐约可见），控件浮在遮罩上：
 *  - 顶部 ≡ 手柄：上下拖 = 键盘高度（底边锚定，上拖变高、下拖变矮，顶边跟手）；
 *  - 底部 ≡ 手柄：**底边跟手**（顶边锚定）——上拖底边上移（变矮+抬离）、下拖贴回
 *    （贴底即止）；高度与底部留白耦合增减（[KeyboardResizeBounds.bottomEdgeDragDelta]），
 *    总高恒定 ⇒ 顶边不动、键盘不整体位移；
 *  - 左 / 右 ||| 手柄：各自独立调所在侧边距（边跟手，往内拖变窄、往外拖变宽，
 *    两侧不等宽时键盘整体偏移）；
 *  - 「重置 / 取消 / 确定」：在遮罩宽度内均分（SpaceEvenly），遮罩变窄时间距自动压缩。
 *
 * 可操作性约束：
 *  - 手柄触控区**整体位于遮罩内侧**（键盘上边缘之外是宿主应用，IME 窗口收不到触摸）；
 *  - 拖动回调上报**原始位移 (dx, dy)**（手指向下/向右为正），方向语义由各手柄调用处
 *    决定，中转层不做取反（双重取反曾导致底部方向相反）；
 *  - 组件不持有尺寸状态：入参即预览值，拖动每帧回调新的绝对值，调用方更新状态后
 *    回流入参实现实时预览；边界收敛见 [KeyboardResizeBounds]。
 */
@Composable
fun KeyboardResizeOverlay(
    heightDp: Int,
    bottomPaddingDp: Int,
    marginStartDp: Int,
    marginEndDp: Int,
    onHeightChange: (Int) -> Unit,
    onBottomPaddingChange: (Int) -> Unit,
    onMarginStartChange: (Int) -> Unit,
    onMarginEndChange: (Int) -> Unit,
    onReset: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    val isLandscape = screenWidthDp > screenHeightDp

    val heightBounds = remember(screenHeightDp, isLandscape) {
        KeyboardResizeBounds.heightBoundsDp(screenHeightDp, isLandscape)
    }
    val maxMargin = remember(screenWidthDp) {
        KeyboardResizeBounds.maxHorizontalMarginDp(screenWidthDp)
    }

    // 拖动手势回调里读到的一定是最新值（状态回流入参晚于下一帧手势时防串值）
    val latestHeight by rememberUpdatedState(heightDp)
    val latestBottomPadding by rememberUpdatedState(bottomPaddingDp)
    val latestMarginStart by rememberUpdatedState(marginStartDp)
    val latestMarginEnd by rememberUpdatedState(marginEndDp)
    val latestOnHeightChange by rememberUpdatedState(onHeightChange)
    val latestOnBottomPaddingChange by rememberUpdatedState(onBottomPaddingChange)
    val latestOnMarginStartChange by rememberUpdatedState(onMarginStartChange)
    val latestOnMarginEndChange by rememberUpdatedState(onMarginEndChange)

    // 遮罩 = 调节区域本身：蒙住键盘、吞掉全部点击（调节期间按键不响应），
    // 四条手柄与按钮都是它的子级，随遮罩伸缩
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        // 顶部手柄：调高度（上拖变高、下拖变矮，顶边跟手）。
        // 触控区整体在遮罩内侧：上边缘之外是宿主应用，IME 摸不到
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HANDLE_TOUCH_DP)
                .dragging { _, dy ->
                    val newHeight = (latestHeight - dy).roundToInt()
                        .coerceIn(heightBounds.first, heightBounds.last)
                    latestOnHeightChange(newHeight)
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            GripPill(
                modifier = Modifier.padding(top = 6.dp),
                verticalBars = false,
            )
        }

        // 底部手柄：底边跟手（顶边锚定）——上拖底边上移（变矮+抬离）、下拖底边下移
        // （变高+贴回，贴底后到底为止）。高度与留白耦合增减，总高恒定 ⇒ 顶边不动。
        // 区块锚定"按键区底边"（区域底边 - 留白），拖动时随底边一起移动
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset(y = -bottomPaddingDp.dp)
                .height(HANDLE_TOUCH_DP)
                .dragging { _, dy ->
                    val delta = KeyboardResizeBounds.bottomEdgeDragDelta(
                        dyDp = dy,
                        heightDp = latestHeight,
                        heightBounds = heightBounds,
                        bottomPaddingDp = latestBottomPadding,
                    )
                    latestOnHeightChange((latestHeight + delta).roundToInt())
                    latestOnBottomPaddingChange((latestBottomPadding - delta).roundToInt())
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            GripPill(
                modifier = Modifier.padding(bottom = 6.dp),
                verticalBars = false,
            )
        }

        // 左侧手柄：只调左边距（边跟手：往内/右拖变窄，往外/左拖变宽）。
        // 触控带全高便于抓取；握条点在遮罩垂直居中
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(HANDLE_TOUCH_DP)
                .dragging { dx, _ ->
                    val newStart = (latestMarginStart + dx).roundToInt()
                        .coerceIn(0, maxMargin)
                    latestOnMarginStartChange(newStart)
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            GripPill(
                modifier = Modifier.padding(start = 6.dp),
                verticalBars = true,
            )
        }

        // 右侧手柄：只调右边距（边跟手：往内/左拖变窄，往外/右拖变宽）
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(HANDLE_TOUCH_DP)
                .dragging { dx, _ ->
                    val newEnd = (latestMarginEnd - dx).roundToInt()
                        .coerceIn(0, maxMargin)
                    latestOnMarginEndChange(newEnd)
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            GripPill(
                modifier = Modifier.padding(end = 6.dp),
                verticalBars = true,
            )
        }

        // 操作按钮：在遮罩内均分（SpaceEvenly），遮罩变窄时间距自动压缩，
        // 始终完整落在遮罩内部；垂直锚定**按键区**中心（遮罩顶部往下 heightDp/2，
        // 而非含底部留白的整个遮罩中心）——拖底部手柄、按键区缩小时按钮跟着自适应
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (heightDp / 2 - ACTION_BLOCK_HALF_HEIGHT).dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ResizeActionButton(
                icon = Icons.Default.RestartAlt,
                label = "重置",
                onClick = onReset,
            )
            ResizeActionButton(
                icon = Icons.Default.Close,
                label = "取消",
                onClick = onCancel,
            )
            ResizeActionButton(
                icon = Icons.Default.Check,
                label = "确定",
                onClick = onConfirm,
            )
        }
    }
}

/** 手柄触控区边长（dp）：整条边在遮罩内侧的热区，握点图标贴边居中。 */
private val HANDLE_TOUCH_DP = 48.dp

/** 按钮组总高的一半（56dp 圆 + 6dp 间距 + ~16dp 标签 ≈ 78dp），用于垂直居中。 */
private const val ACTION_BLOCK_HALF_HEIGHT = 39

/**
 * 拖拽手势修饰符：把**未经变换的**位移 (dx, dy)（dp）交给 onDrag。
 * 手指向下/向右为正；方向语义（高度"上拖为增"等）由调用处决定，这里不做任何取反，
 * 避免出现双重取反导致的"反向"。
 */
@Composable
private fun Modifier.dragging(onDrag: (dx: Float, dy: Float) -> Unit): Modifier {
    val density = LocalDensity.current
    return pointerInput(density) {
        detectDragGestures(
            onDrag = { change, dragAmount ->
                change.consume()
                onDrag(
                    with(density) { dragAmount.x.toDp().value },
                    with(density) { dragAmount.y.toDp().value },
                )
            },
        )
    }
}

/**
 * 三道握条（≡ / |||），背后垫深色胶囊。在 55% 遮罩上以白色握条保证可见性。
 * [verticalBars] = true 为左右边缘的竖向握条。
 */
@Composable
private fun GripPill(verticalBars: Boolean, modifier: Modifier = Modifier) {
    val barColor = Color.White.copy(alpha = 0.95f)
    val barShape = RoundedCornerShape(1.dp)
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.32f), RoundedCornerShape(9.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        if (verticalBars) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) {
                    Box(Modifier.size(2.dp, 16.dp).clip(barShape).background(barColor))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) {
                    Box(Modifier.size(16.dp, 2.dp).clip(barShape).background(barColor))
                }
            }
        }
    }
}

/** 调节操作按钮：深色圆形衬底 + 白色图标 + 下方带阴影的文字标签。 */
@Composable
private fun ResizeActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium.copy(
                shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), blurRadius = 8f),
            ),
        )
    }
}
