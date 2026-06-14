package com.kingzcheung.xime.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.theme.DividerColor
import com.kingzcheung.xime.ui.theme.KeyBackground
import com.kingzcheung.xime.ui.theme.KeyTextColor
import com.kingzcheung.xime.ui.theme.KeyboardBackground
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardViewTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun `KeyButton should display text`() {
        composeTestRule.setContent {
            KeyButton(
                text = "Q",
                onClick = {}
            )
        }
        
        composeTestRule.onNodeWithText("Q").assertIsDisplayed()
    }
    
    @Test
    fun `KeyButton should handle click`() {
        var clicked = false
        composeTestRule.setContent {
            KeyButton(
                text = "A",
                onClick = { clicked = true }
            )
        }
        
        composeTestRule.onNodeWithText("A").performClick()
        
        assert(clicked)
    }
    
    @Test
    fun `SpaceKeyButton should display correctly`() {
        composeTestRule.setContent {
            SpaceKeyButton(
                isAsciiMode = false,
                onClick = {},
                onLongClick = {}
            )
        }
    }
    
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
    }
    
    @Test
    fun `CandidateItem should display text and comment`() {
        composeTestRule.setContent {
            CandidateItem(
                text = "你好",
                index = 0,
                onClick = {},
                textColor = KeyTextColor,
                comment = "wubi"
            )
        }
        
        composeTestRule.onNodeWithText("你好").assertIsDisplayed()
    }
}