package com.kingzcheung.xime.calculator

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 使用 calc.csv 中的数据驱动测试 [CalculatorEngine.evaluate]。
 * CSV 格式：expression_1,result_1,expression_2,result_2,...,expression_5,result_5
 * 每行 5 组 (表达式, 预期结果)，共 10 行 = 50 条用例。
 */
class CalculatorCsvTest {

    @Test
    fun `calc csv 全部表达式验证`() {
        val csvText = javaClass.classLoader?.getResource("calc.csv")?.readText()
            ?: throw IllegalStateException("找不到 calc.csv，请确认文件在 test/resources/ 下")
        val lines = csvText.lines()
        val dataLines = lines.drop(1)

        var total = 0
        var failed = 0
        val errors = mutableListOf<String>()

        for ((rowIdx, line) in dataLines.withIndex()) {
            val values = parseCsvLine(line)
            // 每行 10 个值: expr1, result1, expr2, result2, ...
            require(values.size == 10) { "行 $rowIdx 应有 10 列，实际 ${values.size}" }

            for (col in 0 until 5) {
                val expr = values[col * 2]
                val expected = values[col * 2 + 1]
                total++

                val actual = CalculatorEngine().evaluate(expr)
                if (actual == null || actual != expected) {
                    failed++
                    errors.add("行${rowIdx + 2}列${col + 1}: \"$expr\" → 期望 $expected, 实际 $actual")
                }
            }
        }

        if (failed > 0) {
            val msg = buildString {
                appendLine("$failed / $total 条失败:")
                errors.forEach { appendLine("  $it") }
            }
            throw AssertionError(msg)
        }
        // 全部通过时不输出
    }

    /**
     * 解析 CSV 行，处理引号包裹的字段。
     * 支持: "expression with spaces",123,"another expr",456
     */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes -> inQuotes = false
                c == ',' && !inQuotes -> {
                    result.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) result.add(current.toString().trim())
        return result
    }
}
