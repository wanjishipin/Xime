package com.kingzcheung.xime.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.random.Random

class VoiceKeyboardContainer(
    context: Context,
    private val uiStateProvider: () -> InputUIState,
    private val onUiStateChanged: (InputUIState) -> Unit,
    private val onPerformVibration: () -> Unit,
    private val onPerformUndo: () -> Unit,
    private val onPerformSearch: () -> Unit,
    private val onStopRecognition: () -> Unit,
    private val isRecording: () -> Boolean,
    private val setRecording: (Boolean) -> Unit
) : FrameLayout(context) {

    private var isTrackingVoiceButtons = false
    private var lastLeftActive = false
    private var lastRightActive = false

    private val rectF = RectF()
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f
    }

    private var noiseBitmap: Bitmap? = null
    private var noiseWidth = 0
    private var noiseHeight = 0

    private fun getNoiseBitmap(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        if (noiseBitmap != null && noiseWidth == w && noiseHeight == h) {
            return noiseBitmap
        }
        noiseBitmap?.recycle()
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        val pixels = IntArray(w * h)
        val rng = Random(System.nanoTime())
        for (i in pixels.indices) {
            val n = rng.nextInt(40)
            pixels[i] = Color.argb(n, 255, 255, 255)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        noiseBitmap = bitmap
        noiseWidth = w
        noiseHeight = h
        return bitmap
    }

    fun enableVoiceButtonTracking() {
        isTrackingVoiceButtons = true
    }

    fun updateHeight(heightDp: Int) {
        val heightPx = (heightDp * resources.displayMetrics.density).toInt()
        val params = layoutParams
        if (params != null && params.height != heightPx) {
            params.height = heightPx
            layoutParams = params
            requestLayout()
        }
    }

    private fun isEffectivelyDark(): Boolean {
        val state = uiStateProvider()
        return when (state.darkMode) {
            1 -> true
            0 -> false
            else -> {
                val nightMode = resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        if (!uiStateProvider().isGlassEffectEnabled) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        rectF.set(0f, 0f, w, h)

        if (isEffectivelyDark()) {
            drawFrostedGlass(canvas, w, h, dark = true)
        } else {
            drawFrostedGlass(canvas, w, h, dark = false)
        }
    }

    private fun drawFrostedGlass(canvas: Canvas, w: Float, h: Float, dark: Boolean) {
        val noiseAlpha = if (dark) 28 else 35
        val noiseBmp = getNoiseBitmap(w.toInt(), h.toInt())
        if (noiseBmp != null) {
            val noisePaint = Paint()
            noisePaint.alpha = noiseAlpha
            canvas.drawBitmap(noiseBmp, 0f, 0f, noisePaint)
        }

        if (dark) {
            glassPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0x2A000000, 0x18000000, 0x30FFFFFF),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            glassPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0x2DFFFFFF, 0x14FFFFFF, 0x2A000000),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rectF, glassPaint)

        if (dark) {
            glassPaint.shader = LinearGradient(
                0f, 0f, 0f, h * 0.08f,
                intArrayOf(0x3CFFFFFF, 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            glassPaint.shader = LinearGradient(
                0f, 0f, 0f, h * 0.10f,
                intArrayOf(0x28000000, 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rectF, glassPaint)

        val borderCol = if (dark) 0x30FFFFFF.toInt() else 0x18000000
        borderPaint.color = borderCol
        canvas.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, 2f, 2f, borderPaint)

        glassPaint.shader = null
        if (dark) {
            glassPaint.color = Color.argb(28, 140, 160, 200)
        } else {
            glassPaint.color = Color.argb(20, 200, 210, 235)
        }
        canvas.drawRect(rectF, glassPaint)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            when (it.action) {
                MotionEvent.ACTION_DOWN -> {
                    handleActionDown(it)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handleActionUp()
                }

                MotionEvent.ACTION_MOVE -> {
                    handleActionMove(it)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun handleActionDown(ev: MotionEvent) {
        val isVoiceMode = uiStateProvider().isVoiceMode

        lastLeftActive = false
        lastRightActive = false

        if (isVoiceMode) {
            val yThreshold = height * 0.6f

            if (ev.y > yThreshold) {
                isTrackingVoiceButtons = true
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState(bottomActive = true)
                ))
            }
        }
    }

    private fun handleActionUp() {
        val state = uiStateProvider()

        if (state.isVoiceMode || isRecording()) {
            if (state.voiceButtonState.leftActive) {
                onPerformUndo()
            } else if (state.voiceButtonState.rightActive) {
                onPerformSearch()
            }

            if (isRecording()) {
                onStopRecognition()
                setRecording(false)
            }
        }

        isTrackingVoiceButtons = false
        lastLeftActive = false
        lastRightActive = false
    }

    private fun handleActionMove(ev: MotionEvent) {
        val isVoiceMode = uiStateProvider().isVoiceMode

        if (isVoiceMode && isTrackingVoiceButtons) {
            val yThreshold = height * 0.6f
            val leftButtonEnd = width * 0.25f
            val rightButtonStart = width * 0.75f

            if (ev.y > yThreshold) {
                when {
                    ev.x < leftButtonEnd -> {
                        if (!lastLeftActive) {
                            onPerformVibration()
                            lastLeftActive = true
                        }
                        onUiStateChanged(uiStateProvider().copy(
                            voiceButtonState = VoiceButtonState(leftActive = true)
                        ))
                    }
                    ev.x > rightButtonStart -> {
                        if (!lastRightActive) {
                            onPerformVibration()
                            lastRightActive = true
                        }
                        onUiStateChanged(uiStateProvider().copy(
                            voiceButtonState = VoiceButtonState(rightActive = true)
                        ))
                    }
                    else -> {
                        lastLeftActive = false
                        lastRightActive = false
                        onUiStateChanged(uiStateProvider().copy(
                            voiceButtonState = VoiceButtonState(bottomActive = true)
                        ))
                    }
                }
            } else if (ev.x < leftButtonEnd) {
                if (!lastLeftActive) {
                    onPerformVibration()
                    lastLeftActive = true
                }
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState(leftActive = true)
                ))
            } else if (ev.x > rightButtonStart) {
                if (!lastRightActive) {
                    onPerformVibration()
                    lastRightActive = true
                }
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState(rightActive = true)
                ))
            } else {
                lastLeftActive = false
                lastRightActive = false
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState()
                ))
            }
        }
    }
}
