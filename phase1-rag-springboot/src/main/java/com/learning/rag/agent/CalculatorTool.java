package com.learning.rag.agent;

import org.springframework.stereotype.Component;

/**
 * 计算器工具
 *
 * 演示"工具调用"最简单的例子：LLM 不擅长精确计算
 * （它是预测下一个词，不是真的算），所以把计算交给真正的计算器。
 *
 * 支持基本四则运算，如 "6379 * 2"、"(100 + 50) / 3"
 */
@Component
public class CalculatorTool implements Tool {

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "计算器，用于精确的数学运算。" +
                "当需要做加减乘除等数学计算时使用。" +
                "输入是一个数学表达式，例如：6379 * 2 或 (100 + 50) / 3";
    }

    @Override
    public String execute(String input) {
        try {
            // 用简单的表达式求值（仅支持 + - * / 和括号）
            double result = evaluate(input.trim());
            // 如果是整数，去掉小数点
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                return String.valueOf((long) result);
            }
            return String.valueOf(result);
        } catch (Exception e) {
            return "计算失败：无法解析表达式「" + input + "」，请检查格式";
        }
    }

    /**
     * 简单的四则运算求值器（递归下降解析）
     *
     * 为什么自己写而不用 ScriptEngine？
     * - Java 15+ 移除了内置的 JavaScript 引擎（Nashorn）
     * - 自己实现一个简单的，避免依赖问题，也更安全（不执行任意代码）
     */
    private double evaluate(String expr) {
        return new Parser(expr).parseExpression();
    }

    /**
     * 表达式解析器：支持 + - * / 和括号，遵循运算优先级
     */
    private static class Parser {
        private final String s;
        private int pos = 0;

        Parser(String s) {
            this.s = s.replaceAll("\\s+", "");  // 去掉所有空格
        }

        // 表达式 = 项 (('+' | '-') 项)*
        double parseExpression() {
            double value = parseTerm();
            while (pos < s.length()) {
                char op = s.charAt(pos);
                if (op == '+') { pos++; value += parseTerm(); }
                else if (op == '-') { pos++; value -= parseTerm(); }
                else break;
            }
            return value;
        }

        // 项 = 因子 (('*' | '/') 因子)*
        double parseTerm() {
            double value = parseFactor();
            while (pos < s.length()) {
                char op = s.charAt(pos);
                if (op == '*') { pos++; value *= parseFactor(); }
                else if (op == '/') { pos++; value /= parseFactor(); }
                else break;
            }
            return value;
        }

        // 因子 = 数字 | '(' 表达式 ')'
        double parseFactor() {
            if (s.charAt(pos) == '(') {
                pos++;  // 跳过 '('
                double value = parseExpression();
                pos++;  // 跳过 ')'
                return value;
            }
            // 解析数字（含负号和小数点）
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') pos++;
            while (pos < s.length() &&
                    (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
