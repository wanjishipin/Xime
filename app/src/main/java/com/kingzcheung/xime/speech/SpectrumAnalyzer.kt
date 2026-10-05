package com.kingzcheung.xime.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 轻量 FFT 频谱分析器：把一帧 PCM 采样拆成 [bandCount] 个对数频段，
 * 每个频段输出 0~1 的归一化能量，供频谱可视化使用。
 *
 * 非线程安全：单线程（录音线程）逐帧调用。
 */
class SpectrumAnalyzer(
    private val fftSize: Int = 512,
    private val sampleRate: Int = 16000,
    private val bandCount: Int = 16,
) {
    private val bandEdges: IntArray
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val hannWindow = FloatArray(fftSize) { i ->
        0.5f - 0.5f * cos(2.0 * PI * i / (fftSize - 1)).toFloat()
    }

    init {
        // 对数频段边界（80Hz ~ 7.5kHz，避开直流与奈奎斯特边缘）
        val fMin = 80.0
        val fMax = 7500.0
        val binWidth = sampleRate.toDouble() / fftSize
        val edges = IntArray(bandCount + 1)
        for (i in 0..bandCount) {
            val freq = fMin * (fMax / fMin).pow(i.toDouble() / bandCount)
            edges[i] = (freq / binWidth).toInt().coerceIn(0, fftSize / 2 - 1)
        }
        for (i in 1..bandCount) {
            if (edges[i] <= edges[i - 1]) edges[i] = edges[i - 1] + 1
        }
        bandEdges = edges
    }

    /**
     * 分析 [samples]（short 采样），取最新 [fftSize] 个采样做 FFT，
     * 返回 [bandCount] 个 0~1 的频段能量。
     */
    fun analyze(samples: ShortArray, length: Int): FloatArray {
        val n = fftSize
        val offset = (length - n).coerceAtLeast(0)
        for (i in 0 until n) {
            val src = offset + i
            val v = if (src < length) samples[src].toFloat() else 0f
            re[i] = v * hannWindow[i]
            im[i] = 0f
        }
        fft(re, im, n)

        val dbs = FloatArray(bandCount)
        var maxDb = -120f
        for (b in 0 until bandCount) {
            var energy = 0f
            for (bin in bandEdges[b] until bandEdges[b + 1]) {
                energy += re[bin] * re[bin] + im[bin] * im[bin]
            }
            val db = 20f * log10(sqrt(energy) / 32768f + 1e-5f)
            if (db > maxDb) maxDb = db
            dbs[b] = db
        }

        val floorDb = -55f
        val range = (maxDb - floorDb).coerceAtLeast(1f)
        return FloatArray(bandCount) { b ->
            ((dbs[b] - floorDb) / range).coerceIn(0f, 1f)
        }
    }

    /** 迭代 Radix-2 FFT（Cooley–Tukey），原地计算 [re]/[im] */
    private fun fft(re: FloatArray, im: FloatArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()
            for (i in 0 until n step len) {
                var curRe = 1f
                var curIm = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                    val bIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + half] = aRe - bRe
                    im[i + k + half] = aIm - bIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
            }
            len = len shl 1
        }
    }
}
