package com.h.backend.voice.domain;

import lombok.Data;
import com.h.backend.common.exception.BusinessException;

@Data
public class VoiceTurn {
    private String id;
    private String callId;
    private Long runId;
    private Long userMessageId;
    private Long assistantMessageId;
    private String utteranceId;
    private String userText;
    private String generatedText = "";
    private String generationState = "ACCEPTED";
    private String playoutState = "NOT_STARTED";
    private int playedChars;
    private long revision;
    private String confidence = "UNKNOWN";
    private boolean finalPlayout;
    private String state = "OPEN";
    private long createdAt;
    private long updatedAt;

    public boolean generationTerminal() {
        return !"ACCEPTED".equals(generationState) && !"GENERATING".equals(generationState);
    }

    // Counts are Unicode code points, shared with Python len(str), never Java UTF-16 offsets.
    public String effectiveText() {
        return generatedText.substring(0, generatedText.offsetByCodePoints(0, playedChars));
    }

    public void playout(long nextRevision, String nextState, int chars, String alignment, boolean last) {
        int size = generatedText.codePointCount(0, generatedText.length());
        if (nextRevision == revision && chars == playedChars && nextState.equals(playoutState)
                && last == finalPlayout && alignment.equals(confidence)) return;
        if (finalPlayout || nextRevision <= revision || chars < playedChars || chars > size
                || !java.util.Set.of("PLAYING", "COMPLETED", "INTERRUPTED", "UNKNOWN").contains(nextState)
                || !java.util.Set.of("ESTIMATED", "ALIGNED", "UNKNOWN").contains(alignment)
                || last == "PLAYING".equals(nextState)) {
            throw new BusinessException(40900, "播放结果冲突或范围无效");
        }
        if ("COMPLETED".equals(nextState)
                && (!"GENERATED".equals(generationState) || chars != size)) {
            throw new BusinessException(40900, "生成尚未完成，不能确认完整播放");
        }
        revision = nextRevision;
        playedChars = chars;
        playoutState = nextState;
        confidence = alignment;
        finalPlayout = last;
    }
}
