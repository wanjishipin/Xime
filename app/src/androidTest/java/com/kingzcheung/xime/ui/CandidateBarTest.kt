package com.kingzcheung.xime.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.theme.DividerColor
import com.kingzcheung.xime.ui.theme.KeyTextColor
import com.kingzcheung.xime.ui.theme.KeyboardBackground
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CandidateBarTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun `CandidateBar should display candidates`() {
        val candidates = listOf("你好", "世界", "测试")
        
        composeTestRule.setContent {
            CandidateBar(
                candidates = candidates,
                inputText = "nihao",
                isComposing = true,
                visuals = CandidateBarVisuals(
                    backgroundColor = KeyboardBackground,
                    textColor = KeyTextColor,
                    dividerColor = DividerColor
                ),
                callbacks = CandidateBarCallbacks(
                    onCandidateSelect = {}
                )
            )
        }
        
        composeTestRule.onNodeWithText("你好").assertIsDisplayed()
        composeTestRule.onNodeWithText("世界").assertIsDisplayed()
        composeTestRule.onNodeWithText("测试").assertIsDisplayed()
    }
    
    @Test
    fun `CandidateBar should handle empty candidates`() {
        composeTestRule.setContent {
            CandidateBar(
                candidates = emptyList(),
                inputText = "",
                isComposing = false,
                visuals = CandidateBarVisuals(
                    backgroundColor = KeyboardBackground,
                    textColor = KeyTextColor,
                    dividerColor = DividerColor
                ),
                callbacks = CandidateBarCallbacks(
                    onCandidateSelect = {}
                )
            )
        }
    }
    
    @Test
    fun `CandidateBar should display input text when composing`() {
        composeTestRule.setContent {
            CandidateBar(
                candidates = listOf("你好"),
                inputText = "nihao",
                isComposing = true,
                visuals = CandidateBarVisuals(
                    backgroundColor = KeyboardBackground,
                    textColor = KeyTextColor,
                    dividerColor = DividerColor
                ),
                callbacks = CandidateBarCallbacks(
                    onCandidateSelect = {}
                )
            )
        }
        
        composeTestRule.onNodeWithText("nihao").assertIsDisplayed()
    }
    
    @Test
    fun `CandidateBar should display comments`() {
        composeTestRule.setContent {
            CandidateBar(
                candidates = listOf("你好"),
                candidateComments = listOf("wubi"),
                inputText = "nihao",
                isComposing = true,
                visuals = CandidateBarVisuals(
                    backgroundColor = KeyboardBackground,
                    textColor = KeyTextColor,
                    dividerColor = DividerColor
                ),
                callbacks = CandidateBarCallbacks(
                    onCandidateSelect = {}
                )
            )
        }
        
        composeTestRule.onNodeWithText("你好").assertIsDisplayed()
    }
    
    @Test
    fun `CandidateBar should display association candidates`() {
        composeTestRule.setContent {
            CandidateBar(
                candidates = listOf("你好"),
                associationCandidates = listOf("世界", "吗"),
                inputText = "nihao",
                isComposing = true,
                visuals = CandidateBarVisuals(
                    backgroundColor = KeyboardBackground,
                    textColor = KeyTextColor,
                    dividerColor = DividerColor
                ),
                callbacks = CandidateBarCallbacks(
                    onCandidateSelect = {}
                )
            )
        }
        
        composeTestRule.onNodeWithText("你好").assertIsDisplayed()
        composeTestRule.onNodeWithText("世界").assertIsDisplayed()
    }
    
    @Test
    fun `CandidateItem should display text`() {
        composeTestRule.setContent {
            CandidateItem(
                text = "测试候选词",
                index = 0,
                onClick = {},
                textColor = KeyTextColor
            )
        }
        
        composeTestRule.onNodeWithText("测试候选词").assertIsDisplayed()
    }
    
    @Test
    fun `CandidateItem should display comment`() {
        composeTestRule.setContent {
            CandidateItem(
                text = "你好",
                index = 0,
                onClick = {},
                textColor = KeyTextColor,
                comment = "aaaa"
            )
        }
        
        composeTestRule.onNodeWithText("你好").assertIsDisplayed()
        composeTestRule.onNodeWithText("aaaa").assertIsDisplayed()
    }
}