package com.h.otheragents.a2a;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CreativeWriterService {

    public String writeDraft(List<String> prompts) {
        String topic = prompts == null || prompts.isEmpty() ? "" : prompts.getFirst();
        if (topic == null || topic.isBlank()) {
            topic = "一次意外的旅程";
        }

        return """
                《%s》
                城市的霓虹在雨幕里像信号一样闪烁，主角带着一枚失效的导航芯片穿过无人车道。
                当远处的中继塔重新亮起时，所有人都发现真正需要被拯救的不是目的地，而是他们彼此之间快要断线的信任。
                最后一班返航列车抵达前，主角把故事写进公共频道，让迷路的人都能循着这束微光回家。
                """.formatted(topic.trim());
    }
}
