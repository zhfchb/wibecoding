package com.example.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * 计算器工具，演示带参数的 Function Calling。
 */
public class CalculatorTools {

    @Tool("对两个数执行四则运算：add 加法、subtract 减法、multiply 乘法、divide 除法")
    public double calculate(
            @P("第一个操作数") double a,
            @P("运算类型：add / subtract / multiply / divide") String operation,
            @P("第二个操作数") double b) {
        return switch (operation.toLowerCase()) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> b == 0 ? Double.NaN : a / b;
            default -> throw new IllegalArgumentException("不支持的运算: " + operation);
        };
    }
}
