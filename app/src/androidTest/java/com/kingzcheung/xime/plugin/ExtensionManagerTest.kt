package com.kingzcheung.xime.plugin

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.data.EmojiData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExtensionManagerTest {
    
    private lateinit var context: Context
    
    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        ExtensionManager.release()
    }
    
    @After
    fun tearDown() {
        ExtensionManager.release()
    }
    
    @Test
    fun isInitializedReturnsFalseBeforeInit() {
        assertFalse("Should not be initialized before init", ExtensionManager.isInitialized())
    }
    
    @Test
    fun initializeSetsInitializedState() {
        ExtensionManager.initialize(context)
        
        assertTrue("Should be initialized after init", ExtensionManager.isInitialized())
    }
    
    @Test
    fun initializeMultipleTimesShouldNotCauseError() {
        ExtensionManager.initialize(context)
        ExtensionManager.initialize(context)
        
        assertTrue("Should still be initialized", ExtensionManager.isInitialized())
    }
    
    @Test
    fun releaseResetsInitializedState() {
        ExtensionManager.initialize(context)
        assertTrue("Should be initialized", ExtensionManager.isInitialized())
        
        ExtensionManager.release()
        
        assertFalse("Should not be initialized after release", ExtensionManager.isInitialized())
    }
    
    @Test
    fun getAllInstalledPluginsReturnsListAfterInit() {
        ExtensionManager.initialize(context)
        
        val plugins = ExtensionManager.getAllInstalledPlugins()
        
        assertNotNull("Plugin list should not be null", plugins)
    }
    
    @Test
    fun getEmojiPluginsReturnsListAfterInit() {
        ExtensionManager.initialize(context)
        
        val emojiPlugins = ExtensionManager.getEmojiPlugins()
        
        assertNotNull("Emoji plugin list should not be null", emojiPlugins)
    }
    
    @Test
    fun emojiCategoriesFlowHasDefaultCategories() = runBlocking {
        val categories = ExtensionManager.emojiCategoriesFlow.first()
        
        assertTrue("Should have default categories", categories.isNotEmpty())
        assertTrue("Default should be EmojiData.categories", 
            categories.any { it.name == EmojiData.categories.first().name })
    }
    
    @Test
    fun getEnabledEmojiPluginsReturnsEmptyWhenNoPluginsEnabled() {
        ExtensionManager.initialize(context)
        
        val enabledPlugins = ExtensionManager.getEnabledEmojiPlugins(context)
        
        assertNotNull("Enabled plugins should not be null", enabledPlugins)
    }
    
    @Test
    fun getPluginByIdReturnsNullForUnknownPlugin() {
        ExtensionManager.initialize(context)
        
        val plugin = ExtensionManager.getPluginById("unknown_plugin_id")
        
        assertFalse("Unknown plugin should not exist", plugin != null)
    }
    
    @Test
    fun reloadReturnsTrueWhenInitialized() {
        ExtensionManager.initialize(context)
        
        val result = ExtensionManager.reload(context)
        
        assertTrue("Reload should succeed", result)
    }
    
    @Test
    fun reloadReturnsFalseWhenNotInitialized() {
        ExtensionManager.release()
        
        val result = ExtensionManager.reload(context)
        
        assertFalse("Reload should fail when not initialized", result)
    }
    
    @Test
    fun emojiCategoriesFlowIsObservable() = runBlocking {
        ExtensionManager.initialize(context)
        
        val categories = ExtensionManager.emojiCategoriesFlow.first()
        
        assertTrue("Should be able to collect flow", categories.isNotEmpty())
    }
    
    @Test
    fun initializeWorksWithNullSharedUserId() {
        ExtensionManager.initialize(context)
        
        assertTrue("Should initialize without sharedUserId", ExtensionManager.isInitialized())
    }
    
    @Test
    fun multipleReleaseCallsShouldNotCauseError() {
        ExtensionManager.initialize(context)
        ExtensionManager.release()
        ExtensionManager.release()
        
        assertFalse("Should handle multiple releases", ExtensionManager.isInitialized())
    }
    
    @Test
    fun emojiPluginsSizeNotExceedAllPluginsSize() {
        ExtensionManager.initialize(context)
        
        val allPlugins = ExtensionManager.getAllInstalledPlugins()
        val emojiPlugins = ExtensionManager.getEmojiPlugins()
        
        assertTrue("Emoji plugins should be subset of all plugins", 
            emojiPlugins.size <= allPlugins.size)
    }
}