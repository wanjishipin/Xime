package com.kingzcheung.xime.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CandidatePage(
    candidates: List<String>,
    candidateComments: List<String> = emptyList(),
    associationCandidates: List<String> = emptyList(),
    inputText: String,
    onCandidateSelect: (Int) -> Unit,
    onAssociationSelect: ((Int) -> Unit)? = null,
    backgroundColor: Color,
    textColor: Color,
    hasNextPage: Boolean = false,
    hasPrevPage: Boolean = false,
    onPageDown: (() -> Unit)? = null,
    onPageUp: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = textColor == Color(0xFFE8EAED)
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val centerPage = 1
    val pagerState = rememberPagerState(initialPage = centerPage, pageCount = { 3 })

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != centerPage) {
            if (pagerState.currentPage == 0 && hasPrevPage && onPageUp != null) {
                onPageUp()
            } else if (pagerState.currentPage == 2 && hasNextPage && onPageDown != null) {
                onPageDown()
            }
            pagerState.scrollToPage(centerPage)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
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
                            if (hasPrevPage && onPageUp != null) textColor.copy(alpha = 0.5f)
                            else textColor.copy(alpha = 0.1f)
                        )
                        .clickable(
                            enabled = hasPrevPage && onPageUp != null,
                            onClick = { onPageUp?.invoke() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一页",
                        tint = if (hasPrevPage) textColor else textColor.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (hasNextPage && onPageDown != null) textColor.copy(alpha = 0.25f)
                            else textColor.copy(alpha = 0.1f)
                        )
                        .clickable(
                            enabled = hasNextPage && onPageDown != null,
                            onClick = { onPageDown?.invoke() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一页",
                        tint = if (hasNextPage) textColor else textColor.copy(alpha = 0.3f),
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
                    .clickable { onBack?.invoke() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "返回",
                    tint = textColor,
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
                        .padding(bottom = 40.dp)
                ) {
                    if (candidates.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            candidates.forEachIndexed { index, candidate ->
                                CandidatePageItem(
                                    text = candidate,
                                    comment = candidateComments.getOrElse(index) { "" },
                                    onClick = { onCandidateSelect(index) },
                                    textColor = textColor
                                )
                            }
                        }
                    }

                    if (associationCandidates.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            associationCandidates.forEachIndexed { index, candidate ->
                                CandidatePageItem(
                                    text = candidate,
                                    comment = "",
                                    onClick = { onAssociationSelect?.invoke(index) },
                                    textColor = textColor
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
                    bottomPaddingDp,
                    40
                ).dp
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
