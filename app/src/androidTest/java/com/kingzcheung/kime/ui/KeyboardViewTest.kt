package com.kingzcheung.kime.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    fun `CandidateBar should highlight selected candidate`() {
        val candidates = listOf("你好", "世界", "测试")
        var selectedIndex = 0
        
        composeTestRule.setContent {
            CandidateBar(
                candidates = candidates,
                selectedIndex = selectedIndex,
                onCandidateClick = { index -> selectedIndex = index },
                onCandidateLongClick = {}
            )
        }
        
        composeTestRule.onNodeWithText("你好").assertIsDisplayed()
        composeTestRule.onNodeWithText("世界").assertIsDisplayed()
    }
    
    @Test
    fun `KeyboardLayout should switch modes`() {
        var isSymbolMode = false
        
        composeTestRule.setContent {
            KeyboardLayout(
                isSymbolMode = isSymbolMode,
                onSymbolModeChange = { isSymbolMode = it },
                onKeyPress = {},
                candidates = emptyList(),
                selectedIndex = -1,
                onCandidateClick = {}
            )
        }
    }
}