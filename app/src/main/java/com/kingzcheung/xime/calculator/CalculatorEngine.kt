package com.kingzcheung.xime.calculator

import net.objecthunter.exp4j.ExpressionBuilder
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 计算器引擎
 *
 * 状态机：二操作数输入（左 op 右）→ 实时候选。
 * [calculate] 二操作数用 BigDecimal 保证精度。
 * [evaluate] 完整表达式用 exp4j 支持混合运算/括号/优先级。
 */
class CalculatorEngine {

    private var leftOperand = ""
    private var operator_ = ""
    private var rightOperand = ""
    /** 完整的用户输入表达式，如 "44-70+35-70" */
    private var fullExpression = ""

    fun isActive(): Boolean =
        leftOperand.isNotEmpty() && operator_.isNotEmpty() && rightOperand.isNotEmpty()

    fun getResult(): String {
        if (!isActive()) return ""
        return computeResult() ?: ""
    }

    /** 完整的用户输入表达式。 */
    fun getExpression(): String = fullExpression

    fun getFormulaResult(): String {
        if (!isActive()) return ""
        val result = getResult()
        if (result.isEmpty()) return ""
        return "$fullExpression=$result"
    }

    /** 完整表达式候选，如 "44 - 70 + 35 - 70 = -61"。 */
    fun getCandidate(): String? {
        if (!isActive()) return null
        val result = getResult()
        if (result.isEmpty()) return null
        return "$fullExpression = $result"
    }

    fun clear() {
        leftOperand = ""
        operator_ = ""
        rightOperand = ""
        fullExpression = ""
    }

    fun handleDigit(input: String): Boolean {
        if (operator_.isEmpty()) {
            if (input == ".") {
                if (leftOperand.contains(".")) return false
                if (leftOperand.isEmpty()) leftOperand = "0."
                else leftOperand += "."
            } else {
                leftOperand += input
            }
            fullExpression = "$leftOperand$operator_$rightOperand"
            return false
        } else {
            if (input == ".") {
                if (rightOperand.contains(".")) return false
                if (rightOperand.isEmpty()) rightOperand = "0."
                else rightOperand += "."
            } else {
                rightOperand += input
            }
            fullExpression = "$leftOperand$operator_$rightOperand"
            return true
        }
    }

    fun handleOperator(op: String): Boolean {
        if (leftOperand.isEmpty()) return false
        if (isActive()) {
            val numericResult = computeNumericResult()
            if (numericResult != null) {
                leftOperand = numericResult
                operator_ = op
                rightOperand = ""
                fullExpression = "$leftOperand$operator_$rightOperand"
                return false
            }
        }
        operator_ = op
        rightOperand = ""
        fullExpression = "$leftOperand$operator_$rightOperand"
        return false
    }

    /** 链式计算用 BigDecimal 高精度，保留完整精度供后续运算。 */
    private fun computeNumericResult(): String? {
        val l = leftOperand.toBigDecimalOrNull() ?: return null
        val r = rightOperand.toBigDecimalOrNull() ?: return null
        return try {
            val result = when (operator_) {
                "+" -> l + r
                "-" -> l - r
                "*" -> l * r
                "/" -> {
                    if (r.compareTo(BigDecimal.ZERO) == 0) return null
                    l.divide(r, 15, RoundingMode.HALF_UP)
                }
                else -> return null
            }
            result.stripTrailingZeros().toPlainString()
        } catch (_: Exception) {
            null
        }
    }

    fun handleDelete(): Boolean {
        if (rightOperand.isNotEmpty()) {
            rightOperand = rightOperand.dropLast(1)
        } else if (operator_.isNotEmpty()) {
            operator_ = ""
        } else if (leftOperand.isNotEmpty()) {
            leftOperand = leftOperand.dropLast(1)
        }
        fullExpression = "$leftOperand$operator_$rightOperand"
        return isActive()
    }

    /**
     * 二操作数计算。用 BigDecimal 保证精度（避免 0.3/0.1=2.999…）。
     * 无限小数精确到 10 位显示。
     */
    fun calculate(left: String, op: String, right: String): String? {
        val l = left.toBigDecimalOrNull() ?: return null
        val r = right.toBigDecimalOrNull() ?: return null
        return try {
            val result = when (op) {
                "+" -> l + r
                "-" -> l - r
                "*" -> l * r
                "/" -> {
                    if (r.compareTo(BigDecimal.ZERO) == 0) return null
                    try {
                        l.divide(r)
                    } catch (_: ArithmeticException) {
                        l.divide(r, 10, RoundingMode.HALF_UP)
                    }
                }
                else -> return null
            }
            formatBigDecimal(result.stripTrailingZeros())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 完整表达式计算，支持混合运算、括号、优先级。
     * 使用 exp4j 引擎，适合 `2+3*4`、`(10+5)/3`、`2^10` 等。
     */
    fun evaluate(expression: String): String? {
        // 过滤连续运算符等 exp4j 容忍但非法输入
        if (expression.matches(Regex(".*[+\\-*/]{2,}.*")) &&
            !expression.matches(Regex(".*[eE].*"))) return null
        return try {
            val raw = ExpressionBuilder(expression).build().evaluate()
            if (raw.isInfinite() || raw.isNaN()) return null
            // 四舍五入到 10 位消除浮点误差
            val bd = BigDecimal(raw).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros()
            bd.toPlainString()
        } catch (_: Exception) {
            null
        }
    }

    private fun computeResult(): String? {
        if (leftOperand.isEmpty() || operator_.isEmpty() || rightOperand.isEmpty()) return null
        return calculate(leftOperand, operator_, rightOperand)
    }

    /** 格式化：整数去 .0，小数最多 10 位。 */
    private fun formatBigDecimal(value: BigDecimal): String {
        val rounded = value.setScale(10, RoundingMode.HALF_UP)
        if (rounded.scale() <= 0 || rounded.stripTrailingZeros().scale() <= 0) {
            return rounded.setScale(0, RoundingMode.HALF_UP).toPlainString()
        }
        return rounded.stripTrailingZeros().toPlainString()
    }
}
