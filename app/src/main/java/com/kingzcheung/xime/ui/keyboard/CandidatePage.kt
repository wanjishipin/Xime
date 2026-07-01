package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars

data class CandidatePageState(
    val candidates: List<String>,
    val candidateComments: List<String> = emptyList(),
    val associationCandidates: List<String> = emptyList(),
    val backgroundColor: Color,
    val textColor: Color,
    val hasNextPage: Boolean = false,
    val hasPrevPage: Boolean = false,
    val bottomPaddingDp: Int = 0,
)

data class CandidatePageCallbacks(
    val onCandidateSelect: (Int) -> Unit,
    val onAssociationSelect: ((Int) -> Unit)? = null,
    val onPageDown: (() -> Unit)? = null,
    val onPageUp: (() -> Unit)? = null,
    val onBack: (() -> Unit)? = null,
)

@Composable
fun CandidatePage(
    state: CandidatePageState,
    callbacks: CandidatePageCallbacks,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = state.textColor == Color(0xFFE8EAED)
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val centerPage = 1
    val pagerState = rememberPagerState(initialPage = centerPage, pageCount = { 3 })

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != centerPage) {
            if (pagerState.currentPage == 0 && state.hasPrevPage && callbacks.onPageUp != null) {
                callbacks.onPageUp()
            } else if (pagerState.currentPage == 2 && state.hasNextPage && callbacks.onPageDown != null) {
                callbacks.onPageDown()
            }
            pagerState.scrollToPage(centerPage)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(state.backgroundColor)
    ) {
        // 导航区
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = if (isLandscape) 50.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Spacer(modifier = Modifier.weight(1f))

            // 翻页按钮
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.hasPrevPage && callbacks.onPageUp != null) state.textColor.copy(alpha = 0.5f)
                            else state.textColor.copy(alpha = 0.1f)
                        )
                        .clickable(
                            enabled = state.hasPrevPage && callbacks.onPageUp != null,
                            onClick = { callbacks.onPageUp?.invoke() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一页",
                        tint = if (state.hasPrevPage) state.textColor else state.textColor.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.hasNextPage && callbacks.onPageDown != null) state.textColor.copy(alpha = 0.25f)
                            else state.textColor.copy(alpha = 0.1f)
                        )
                        .clickable(
                            enabled = state.hasNextPage && callbacks.onPageDown != null,
                            onClick = { callbacks.onPageDown?.invoke() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一页",
                        tint = if (state.hasNextPage) state.textColor else state.textColor.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                    .clickable { callbacks.onBack?.invoke() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "返回",
                    tint = state.textColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) { page ->
            if (page == centerPage) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                ) {
                    if (state.candidates.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.candidates.forEachIndexed { index, candidate ->
                                CandidatePageItem(
                                    text = candidate,
                                    comment = state.candidateComments.getOrElse(index) { "" },
                                    onClick = { callbacks.onCandidateSelect(index) },
                                    textColor = state.textColor
                                )
                            }
                        }
                    }

                    if (state.associationCandidates.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.associationCandidates.forEachIndexed { index, candidate ->
                                CandidatePageItem(
                                    text = candidate,
                                    comment = "",
                                    onClick = { callbacks.onAssociationSelect?.invoke(index) },
                                    textColor = state.textColor
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 底部留空
        Spacer(
            modifier = Modifier.height(
                if (isLandscape) 15.dp else maxOf(
                    state.bottomPaddingDp.dp,
                    with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
                )
            )
        )
    }
}

@Composable
fun CandidatePageItem(
    text: String,
    comment: String = "",
    onClick: () -> Unit,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val displayComment = comment.replace("~", "")

    Row(

        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
//            .background(textColor.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier
                .padding(horizontal = 2.dp)
        )
        if (displayComment.isNotEmpty()) {
            Text(
                text = displayComment,
                color = textColor.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .padding(horizontal = 1.dp)
            )
        }
    }
}
