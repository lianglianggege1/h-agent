package com.h.backend.voice;
import com.h.backend.voice.domain.VoiceTurn;
import com.h.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoiceTurnTest {
    @Test void interruptionUsesUnicodeCodepointsAndIsIdempotent() {
        var turn = new VoiceTurn();
        turn.setGeneratedText("你好😀世界");
        turn.playout(1, "INTERRUPTED", 3, "ESTIMATED", true);
        assertEquals("你好😀", turn.effectiveText());
        turn.playout(1, "INTERRUPTED", 3, "ESTIMATED", true);
        assertThrows(BusinessException.class, () -> turn.playout(2, "COMPLETED", 5, "ESTIMATED", true));
    }
    @Test void cannotCommitUnfinishedOrUnplayedGeneration() {
        var turn = new VoiceTurn();
        turn.setGeneratedText("你好世界");
        assertThrows(BusinessException.class, () -> turn.playout(1, "COMPLETED", 4, "ESTIMATED", true));
        turn.setGenerationState("GENERATED");
        assertThrows(BusinessException.class, () -> turn.playout(1, "COMPLETED", 3, "ESTIMATED", true));
        turn.playout(1, "COMPLETED", 4, "ESTIMATED", true);
        assertEquals("你好世界", turn.effectiveText());
    }
    @Test void rejectRegressingOrOutOfRangePlayout() {
        var turn = new VoiceTurn(); turn.setGeneratedText("你好世界");
        turn.playout(1, "PLAYING", 2, "ESTIMATED", false);
        assertThrows(BusinessException.class, () -> turn.playout(2, "PLAYING", 1, "ESTIMATED", false));
        assertThrows(BusinessException.class, () -> turn.playout(2, "INTERRUPTED", 9, "ESTIMATED", true));
        assertThrows(BusinessException.class, () -> turn.playout(2, "PLAYING", 3, "ESTIMATED", true));
    }
}
