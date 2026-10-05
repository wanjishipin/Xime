package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下滑撤回删除的纯逻辑：
 * - [DeleteUndo.removedPrefix]：会话首尾两次快照之差即被删内容
 * - [DeleteUndo.anchorMatches]：撤回落点校验（防插错位置）
 */
class DeleteUndoTest {

    // ── removedPrefix ──

    @Test
    fun `长按连删取被删后缀`() {
        // 会话开始前光标前是 hello world，长按删掉 world → 记 world
        assertEquals("world", DeleteUndo.removedPrefix("hello world", "hello "))
    }

    @Test
    fun `未删除时为空`() {
        assertEquals("", DeleteUndo.removedPrefix("hello", "hello"))
    }

    @Test
    fun `末尾追加过文本则不认账`() {
        // after 不是 before 的前缀（窗口被截断 / 输入框被改写 / 光标移动）→ 放弃记账
        assertEquals("", DeleteUndo.removedPrefix("hello", "hello world"))
    }

    @Test
    fun `中间被改写则不认账`() {
        assertEquals("", DeleteUndo.removedPrefix("hello world", "hello 地球"))
    }

    @Test
    fun `代理对与emoji按 code unit 计数`() {
        // KEYCODE_DEL 在多数宿主按码点删（emoji = 2 code unit），快照差天然算对
        assertEquals("😀", DeleteUndo.removedPrefix("你好😀", "你好"))
    }

    @Test
    fun `全删到空取整段`() {
        assertEquals("abc", DeleteUndo.removedPrefix("abc", ""))
    }

    // ── anchorMatches ──

    @Test
    fun `落点一致允许回插`() {
        assertTrue(DeleteUndo.anchorMatches("你好", "你好"))
    }

    @Test
    fun `落点不一致放弃回插`() {
        // 长按删除后又打过字：光标前文本变了，旧内容插这里会错位
        assertFalse(DeleteUndo.anchorMatches("你好", "你好abc"))
    }

    @Test
    fun `移动光标后放弃回插`() {
        assertFalse(DeleteUndo.anchorMatches("hello", "he"))
    }

    @Test
    fun `快照为空时要求光标仍在开头`() {
        assertTrue(DeleteUndo.anchorMatches("", ""))
        assertFalse(DeleteUndo.anchorMatches("", "你好"))
    }

    @Test
    fun `读不到输入框文本时放行`() {
        // 部分宿主的 getTextBeforeCursor 返回 null：退回改动前行为，不挡住撤回
        assertTrue(DeleteUndo.anchorMatches(null, "任意"))
        assertTrue(DeleteUndo.anchorMatches("任意", null))
        assertTrue(DeleteUndo.anchorMatches(null, null))
    }
}
