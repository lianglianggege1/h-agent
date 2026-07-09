package com.h.otheragents.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AdditionMcpTool {

    @Tool(name = "add_numbers", description = "Add two integer numbers and return the sum.")
    public int addNumbers(
            @ToolParam(description = "The first addend.") int a,
            @ToolParam(description = "The second addend.") int b) {
        return a + b;
    }
}
