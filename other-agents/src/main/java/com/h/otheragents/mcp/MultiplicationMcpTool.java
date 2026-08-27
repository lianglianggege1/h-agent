package com.h.otheragents.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class MultiplicationMcpTool {

    @Tool(name = "multiply_numbers", description = "Multiply two integer numbers and return the product.")
    public int multiplyNumbers(
            @ToolParam(description = "The first factor.") int a,
            @ToolParam(description = "The second factor.") int b) {
        return a * b;
    }
}
