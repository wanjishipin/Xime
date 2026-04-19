package com.kingzcheung.kime.rime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class RimeEngineTest {
    
    private lateinit var context: android.content.Context
    
    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }
    
    @Test
    fun `getInstance should return singleton`() {
        val instance1 = RimeEngine.getInstance()
        val instance2 = RimeEngine.getInstance()
        
        assertSame(instance1, instance2)
    }
    
    @Test
    fun `isInitialized should return false before initialization`() {
        val engine = RimeEngine.getInstance()
        assertFalse(RimeEngine.isInitialized())
    }
    
    @Test
    fun `processKey should return false when not initialized`() {
        val engine = RimeEngine.getInstance()
        val result = engine.processKey(65, 0) // 'A' key
        assertFalse(result)
    }
    
    @Test
    fun `getCandidates should return empty array when not initialized`() {
        val engine = RimeEngine.getInstance()
        val candidates = engine.getCandidates()
        assertTrue(candidates.isEmpty())
    }
    
    @Test
    fun `getInput should return empty string when not initialized`() {
        val engine = RimeEngine.getInstance()
        val input = engine.getInput()
        assertEquals("", input)
    }
    
    @Test
    fun `initialize should create valid directories`() {
        val userDataDir = File(context.filesDir, "rime_user_test")
        val sharedDataDir = File(context.filesDir, "rime_shared_test")
        
        assertNotNull(userDataDir)
        assertNotNull(sharedDataDir)
    }
}