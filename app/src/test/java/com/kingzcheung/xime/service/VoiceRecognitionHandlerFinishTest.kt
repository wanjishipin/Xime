package com.kingzcheung.xime.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.then
import org.mockito.kotlin.whenever

/**
 * 语音松手收尾状态机测试：finishRecognition 停止送音后等待引擎最终结果，
 * 超时才回退提交部分结果（修复"说完立刻松手，尾部语音被截断"）。
 */
@RunWith(MockitoJUnitRunner.Silent::class)
class VoiceRecognitionHandlerFinishTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPrefs: SharedPreferences

    @Mock
    private lateinit var mockInputConnection: InputConnection

    @Mock
    private lateinit var mockManager: SpeechRecognitionManager

    @Mock
    private lateinit var mockMainHandler: Handler

    private lateinit var handler: VoiceRecognitionHandler

    private val stateChanges = mutableListOf<InputUIState>()
    private var voiceCompleteCount = 0

    /** mock mainHandler 的 postDelayed 队列，可手动推进超时。 */
    private val posted = mutableListOf<Runnable>()

    private lateinit var onResult: (String) -> Unit
    private lateinit var onPartial: (String) -> Unit
    private lateinit var onState: (RecognitionState) -> Unit
    private lateinit var onError: (String, Boolean) -> Unit

    @Before
    fun setup() {
        whenever(mockContext.getSharedPreferences(any(), anyInt())).thenReturn(mockPrefs)
        // 所有布尔设置返回默认值；isSttUseLocal 置 true 使 resolveProviderName 走本地引擎
        // 早退（不触碰未初始化的 PluginManager/ExtensionManager），warmup 分支为异步线程无副作用
        whenever(mockPrefs.getBoolean(any(), any<Boolean>())).thenAnswer { it.getArgument(1) }
        whenever(mockPrefs.getBoolean(eq(SettingsPreferences.KEY_STT_USE_LOCAL), any<Boolean>()))
            .thenReturn(true)
        whenever(mockMainHandler.postDelayed(any<Runnable>(), anyLong())).thenAnswer {
            posted.add(it.getArgument(0))
            true
        }
        whenever(mockMainHandler.removeCallbacks(any<Runnable>())).thenAnswer {
            posted.remove(it.getArgument<Runnable>(0))
        }

        handler = VoiceRecognitionHandler(
            context = mockContext,
            onStateChanged = { stateChanges.add(it) },
            getState = { InputUIState() },
            getInputConnection = { mockInputConnection },
            onVoiceComplete = { voiceCompleteCount++ },
            managerFactory = { mockManager },
            mainHandler = mockMainHandler
        )
        handler.initialize()

        val onResultCaptor = argumentCaptor<(String) -> Unit>()
        val onPartialCaptor = argumentCaptor<(String) -> Unit>()
        val onStateCaptor = argumentCaptor<(RecognitionState) -> Unit>()
        val onErrorCaptor = argumentCaptor<(String, Boolean) -> Unit>()
        verify(mockManager).setCallbacks(
            onResultCaptor.capture(), onPartialCaptor.capture(), onStateCaptor.capture(),
            onErrorCaptor.capture(), any(), any()
        )
        onResult = onResultCaptor.firstValue
        onPartial = onPartialCaptor.firstValue
        onState = onStateCaptor.firstValue
        onError = onErrorCaptor.firstValue
    }

    private fun runTimeouts() {
        val copy = posted.toList()
        posted.clear()
        copy.forEach { it.run() }
    }

    @Test
    fun `收尾时收到最终结果则提交完整结果并取消超时兜底`() {
        onPartial.invoke("你好")
        handler.finishRecognition()

        // 停止送音、进入"正在识别"、安排了超时兜底
        verify(mockManager).stopRecognition()
        assertEquals(RecognitionState.PROCESSING, stateChanges.last().voiceRecognitionState)
        assertEquals(1, posted.size)

        // 引擎吐出最终结果：提交增量部分（partial 已通过 composing 上屏，句号由启发式补齐）
        onResult.invoke("你好世界再见")

        verify(mockInputConnection).finishComposingText()
        verify(mockInputConnection).commitText(eq("世界再见。"), eq(1))
        assertEquals(1, voiceCompleteCount)
        // 超时兜底已被取消，手动推进不再重复提交
        assertTrue(posted.isEmpty())
        runTimeouts()
        verify(mockInputConnection, never()).commitText(eq("你好，"), eq(1))
        assertEquals(1, voiceCompleteCount)
    }

    @Test
    fun `超时未收到最终结果则回退提交部分结果且忽略迟到的最终结果`() {
        onPartial.invoke("你好")
        handler.finishRecognition()
        runTimeouts()

        // 部分结果"你好"已通过 composing 上屏，兜底只补上启发式句号"，"
        verify(mockInputConnection).finishComposingText()
        verify(mockInputConnection).commitText(eq("，"), eq(1))
        assertEquals(1, voiceCompleteCount)

        // 迟到的最终结果被抑制：不再写入输入框（避免重复/错乱）
        onResult.invoke("你好世界再见")
        verify(mockInputConnection, times(1)).commitText(any(), anyInt())
        assertEquals(2, voiceCompleteCount)
    }

    @Test
    fun `无已识别文本时收尾不等待直接结束`() {
        handler.finishRecognition()

        verify(mockManager).stopRecognition()
        assertEquals(1, voiceCompleteCount)
        assertTrue(posted.isEmpty())
        assertFalse(stateChanges.any { it.voiceRecognitionState == RecognitionState.PROCESSING })
    }

    @Test
    fun `收尾中重复调用幂等`() {
        onPartial.invoke("你好")
        handler.finishRecognition()
        handler.finishRecognition()

        verify(mockManager).stopRecognition()
        assertEquals(1, posted.size)
        // 收尾尚未完成（在等最终结果），不触发 onVoiceComplete
        assertEquals(0, voiceCompleteCount)
    }

    @Test
    fun `会话被丢弃后收尾不提交文本且超时兜底失效`() {
        onPartial.invoke("你好")
        handler.abandonSession()
        handler.finishRecognition()

        verify(mockManager).stopRecognition()
        runTimeouts()
        verify(mockInputConnection, never()).commitText(any(), anyInt())
        verify(mockInputConnection, never()).finishComposingText()
        assertEquals(0, voiceCompleteCount)
    }

    @Test
    fun `收尾期间引擎的IDLE状态不覆盖正在识别显示`() {
        onPartial.invoke("你好")
        handler.finishRecognition()
        assertEquals(RecognitionState.PROCESSING, stateChanges.last().voiceRecognitionState)

        // 引擎 stop 过程中回调 IDLE：保持"正在识别..."显示
        onState.invoke(RecognitionState.IDLE)
        assertEquals(RecognitionState.PROCESSING, stateChanges.last().voiceRecognitionState)

        // 其他状态（如 ERROR）不被过滤
        onState.invoke(RecognitionState.ERROR)
        assertEquals(RecognitionState.ERROR, stateChanges.last().voiceRecognitionState)
    }

    @Test
    fun `部分结果写入composing区域并同步到UI状态`() {
        onPartial.invoke("你好")

        verify(mockInputConnection).setComposingText(eq("你好"), eq(1))
        assertEquals("你好", stateChanges.last().voiceRecognizedText)
    }

    @Test
    fun `录音中收到流式最终结果则上屏该句但会话继续`() {
        onPartial.invoke("你好")
        // 用户尚未松手，流式插件按句回调 final：该句上屏，会话必须继续
        onResult.invoke("你好")

        // 提交了增量（composing 收尾 + 补句读），但不结束会话、不停止录音——
        // 若此时触发 onVoiceComplete，松手停止链即失效，录音会一直在后台运行
        verify(mockInputConnection).finishComposingText()
        verify(mockInputConnection).commitText(eq("，"), eq(1))
        verify(mockManager, never()).stopRecognition()
        assertEquals(0, voiceCompleteCount)

        // 随后松手：正常收尾结束会话
        onPartial.invoke("世界")
        handler.finishRecognition()
        verify(mockManager).stopRecognition()
        onResult.invoke("世界")
        assertEquals(1, voiceCompleteCount)
    }

    @Test
    fun `录音中引擎报错则停止录音并结束会话且丢弃迟到结果`() {
        onError.invoke("网络断开", false)

        // 录音中报错：必须显式停止录音并结束会话，否则 UI 恢复后松手停止链失效
        verify(mockManager).stopRecognition()
        assertEquals(1, voiceCompleteCount)

        // 错误后迟到的部分/最终结果不再写入输入框（迟到 final 仍走一次幂等完成）
        onPartial.invoke("你好")
        onResult.invoke("你好世界")
        verify(mockInputConnection, never()).setComposingText(any(), anyInt())
        verify(mockInputConnection, never()).commitText(any(), anyInt())
    }

    // ---- 发送类动作前的封口（sealPendingForSend）----

    @Test
    fun `发送封口立即提交已识别文本并丢弃迟到的最终结果`() {
        onPartial.invoke("你好")
        handler.sealPendingForSend()

        // 立即冲刷 composing：收尾 + 补齐启发式句读（与松手兜底同一条路径）
        verify(mockInputConnection).finishComposingText()
        verify(mockInputConnection).commitText(eq("，"), eq(1))
        // 封口自身不结束会话/不停止录音：收尾交给调用方的 endVoiceSession
        verify(mockManager, never()).stopRecognition()
        assertEquals(0, voiceCompleteCount)

        // 引擎随后才吐出的最终结果被丢弃，不再写入输入框
        onResult.invoke("你好世界再见")
        verify(mockInputConnection, times(1)).commitText(any(), anyInt())
        assertEquals(1, voiceCompleteCount)
    }

    @Test
    fun `收尾等待中封口则立即提交并取消超时兜底`() {
        onPartial.invoke("你好")
        handler.finishRecognition()
        assertEquals(1, posted.size)

        handler.sealPendingForSend()

        // 超时兜底被取消，已识别文本立即落盘（不等引擎最终结果）
        assertTrue(posted.isEmpty())
        verify(mockInputConnection).finishComposingText()
        verify(mockInputConnection).commitText(eq("，"), eq(1))
        assertEquals(0, voiceCompleteCount)

        runTimeouts()
        onResult.invoke("你好世界再见")
        verify(mockInputConnection, times(1)).commitText(any(), anyInt())
        assertEquals(1, voiceCompleteCount)
    }

    @Test
    fun `封口后收尾不再等待引擎最终结果`() {
        onPartial.invoke("你好")
        handler.sealPendingForSend()
        handler.finishRecognition()

        // 已无待提交文本：收尾直接结束，也不安排超时兜底
        verify(mockManager).stopRecognition()
        assertEquals(1, voiceCompleteCount)
        assertTrue(posted.isEmpty())

        runTimeouts()
        verify(mockInputConnection, times(1)).commitText(any(), anyInt())
    }

    @Test
    fun `无已识别文本时封口也丢弃迟到的部分与最终结果`() {
        handler.sealPendingForSend()

        verify(mockInputConnection, never()).commitText(any(), anyInt())
        verify(mockInputConnection, never()).finishComposingText()

        // 动作之后引擎才吐出的结果不该上屏
        onPartial.invoke("你好")
        onResult.invoke("你好")
        verify(mockInputConnection, never()).setComposingText(any(), anyInt())
        verify(mockInputConnection, never()).commitText(any(), anyInt())
        assertEquals(1, voiceCompleteCount)
    }

    @Test
    fun `会话被丢弃后封口不写入任何文本`() {
        onPartial.invoke("你好")
        handler.abandonSession()
        handler.sealPendingForSend()

        verify(mockInputConnection, never()).commitText(any(), anyInt())
        verify(mockInputConnection, never()).finishComposingText()
    }

    // ---- 应用取走/清空输入框后的对账（微信点发送后清空输入框：已发送文本不得回灌）----

    /** 模拟应用侧输入框（光标前文本）：随 setComposingText/commitText 变化，测试可模拟"发送后清空"。 */
    private var fakeBoxText = ""

    private fun wireFakeInputBox() {
        fakeBoxText = ""
        whenever(mockInputConnection.getTextBeforeCursor(anyInt(), anyInt())).thenAnswer {
            val n = it.getArgument<Int>(0)
            fakeBoxText.takeLast(n.coerceAtMost(fakeBoxText.length))
        }
        whenever(mockInputConnection.setComposingText(any(), anyInt())).thenAnswer {
            fakeBoxText = it.getArgument<CharSequence>(0).toString()
            true
        }
        whenever(mockInputConnection.commitText(any(), anyInt())).thenAnswer {
            fakeBoxText += it.getArgument<CharSequence>(0).toString()
            true
        }
    }

    @Test
    fun `应用清空输入框后部分结果只写增量不回写已发送内容`() {
        wireFakeInputBox()

        // 说了"一个人"：整段写入并完成回显核对
        onPartial.invoke("一个人")
        assertEquals("一个人", fakeBoxText)

        // 微信发送后清空输入框（输入法不知情）
        fakeBoxText = ""

        // 继续说话：引擎给的是整段累计结果
        onPartial.invoke("一个人，两个人")

        // 只写新说出来的部分，已发送的"一个人"不再回写
        assertEquals("两个人", fakeBoxText)
        verify(mockInputConnection, never()).setComposingText(eq("一个人，两个人"), anyInt())
        assertEquals("两个人", stateChanges.last().voiceRecognizedText)
    }

    @Test
    fun `应用清空输入框后最终结果只提交增量不整段回写`() {
        wireFakeInputBox()
        onPartial.invoke("一个人")
        fakeBoxText = ""

        onResult.invoke("一个人，两个人。")

        // 不再走"删除已上屏部分 + 整段重写"，已发送内容不会被写回
        verify(mockInputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
        verify(mockInputConnection, never()).commitText(eq("一个人，两个人。"), anyInt())
        assertEquals("两个人。", fakeBoxText)
    }

    @Test
    fun `已发送内容被全部消费时不再落任何字符`() {
        wireFakeInputBox()
        onPartial.invoke("你好")
        fakeBoxText = ""

        // 最终结果相对已发送内容只多一个句号：不该在空框里留下孤立标点
        onResult.invoke("你好。")

        verify(mockInputConnection, never()).commitText(any(), anyInt())
        assertEquals("", fakeBoxText)
    }

    @Test
    fun `应用不回显语音文本时不判定已消费`() {
        // 应用侧读不到我们写的内容（getTextBeforeCursor 恒为空）：不得判定"被清空"，否则内容全丢
        whenever(mockInputConnection.getTextBeforeCursor(anyInt(), anyInt())).thenReturn("")

        onPartial.invoke("一个人")
        onPartial.invoke("一个人，两个人")

        // 行为与修复前一致：整段写入
        verify(mockInputConnection).setComposingText(eq("一个人，两个人"), eq(1))
    }
}
