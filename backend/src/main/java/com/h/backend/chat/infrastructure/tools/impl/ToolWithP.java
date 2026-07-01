package com.h.backend.chat.infrastructure.tools.impl;

import com.h.backend.chat.infrastructure.tools.AdderTool;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Service;

@Service
public class ToolWithP implements AdderTool {

    @Tool
    @Override
    public int add(int a, int b) {
        return a + b;
    }

}
