package com.h.backend.chat.infrastructure.storage;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceRangeTest {

    // ------------------------------------------------------------------
    // 语法解析：四种合法形态
    // ------------------------------------------------------------------

    @Test
    void fullReadRepresentsTheCompleteObject() {
        ResourceRange range = ResourceRange.fullRead();

        assertThat(range.kind()).isEqualTo(ResourceRange.Kind.FULL);
    }

    @Test
    void parsesClosedStartEndRange() {
        ResourceRange range = ResourceRange.fromHeader("bytes=0-499");

        assertThat(range.kind()).isEqualTo(ResourceRange.Kind.START_END);
        assertThat(range.start()).isEqualTo(0L);
        assertThat(range.end()).isEqualTo(499L);
    }

    @Test
    void parsesOpenEndedRange() {
        ResourceRange range = ResourceRange.fromHeader("bytes=500-");

        assertThat(range.kind()).isEqualTo(ResourceRange.Kind.START_TO_END);
        assertThat(range.start()).isEqualTo(500L);
    }

    @Test
    void parsesSuffixRange() {
        ResourceRange range = ResourceRange.fromHeader("bytes=-500");

        assertThat(range.kind()).isEqualTo(ResourceRange.Kind.SUFFIX);
        assertThat(range.suffixLength()).isEqualTo(500L);
    }

    // ------------------------------------------------------------------
    // 审查修复 7c：bytes 单位名大小写宽容（RFC 9110 token 大小写不敏感）
    // 与 OWS 空白宽容
    // ------------------------------------------------------------------

    @Test
    void parsesRangeWithUpperCaseBytesUnit() {
        ResourceRange range = ResourceRange.fromHeader("Bytes=0-499");

        assertThat(range.kind()).isEqualTo(ResourceRange.Kind.START_END);
        assertThat(range.start()).isEqualTo(0L);
        assertThat(range.end()).isEqualTo(499L);
    }

    @Test
    void parsesRangeWithMixedCaseBytesUnitAndPadding() {
        ResourceRange range = ResourceRange.fromHeader("  ByTeS=0-499  ");

        assertThat(range.kind()).isEqualTo(ResourceRange.Kind.START_END);
        assertThat(range.start()).isEqualTo(0L);
        assertThat(range.end()).isEqualTo(499L);
    }

    @Test
    void parsesRangeWithInternalWhitespace() {
        // OWS 宽容：token 周围的空白不应让合法区间退化为 400
        ResourceRange range = ResourceRange.fromHeader("bytes = 0 - 499");

        assertThat(range.kind()).isEqualTo(ResourceRange.Kind.START_END);
        assertThat(range.start()).isEqualTo(0L);
        assertThat(range.end()).isEqualTo(499L);
    }

    @Test
    void parsesOpenAndSuffixRangesWithTolerantUnit() {
        assertThat(ResourceRange.fromHeader("BYTES=500-").kind())
                .isEqualTo(ResourceRange.Kind.START_TO_END);
        assertThat(ResourceRange.fromHeader("Bytes=-500").kind())
                .isEqualTo(ResourceRange.Kind.SUFFIX);
    }

    // ------------------------------------------------------------------
    // malformed：语法级 400 语义
    // ------------------------------------------------------------------

    @Test
    void rejectsMissingBytesUnit() {
        assertThatThrownBy(() -> ResourceRange.fromHeader("items=0-100"))
                .isInstanceOf(ResourceRangeException.class)
                .satisfies(error -> assertThat(((ResourceRangeException) error).reason())
                        .isEqualTo(ResourceRangeException.Reason.MALFORMED));
    }

    @Test
    void rejectsMultipleRanges() {
        assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=0-1,5-6"))
                .isInstanceOf(ResourceRangeException.class)
                .satisfies(error -> assertThat(((ResourceRangeException) error).reason())
                        .isEqualTo(ResourceRangeException.Reason.MALFORMED));
    }

    @Test
    void rejectsNegativeStart() {
        assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=-5-10"))
                .isInstanceOf(ResourceRangeException.class);
    }

    @Test
    void rejectsNegativeEnd() {
        assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=5--10"))
                .isInstanceOf(ResourceRangeException.class);
    }

    @Test
    void rejectsStartGreaterThanEnd() {
        assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=10-5"))
                .isInstanceOf(ResourceRangeException.class)
                .satisfies(error -> assertThat(((ResourceRangeException) error).reason())
                        .isEqualTo(ResourceRangeException.Reason.MALFORMED));
    }

    @Test
    void rejectsBlankAndNullHeaders() {
        assertThatThrownBy(() -> ResourceRange.fromHeader(null)).isInstanceOf(ResourceRangeException.class);
        assertThatThrownBy(() -> ResourceRange.fromHeader("")).isInstanceOf(ResourceRangeException.class);
        assertThatThrownBy(() -> ResourceRange.fromHeader("   ")).isInstanceOf(ResourceRangeException.class);
    }

    @Test
    void rejectsNonNumericBounds() {
        assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=a-b")).isInstanceOf(ResourceRangeException.class);
        assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=1-abc")).isInstanceOf(ResourceRangeException.class);
    }

    @Test
    void rejectsDanglingDash() {
        assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=-")).isInstanceOf(ResourceRangeException.class);
        assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=5-6-7")).isInstanceOf(ResourceRangeException.class);
    }

    @Test
    void malformedMessageDoesNotEchoTheRawHeader() {
        assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=0-1,5-6"))
                .hasMessageNotContaining("bytes=0-1,5-6");
    }

    // ------------------------------------------------------------------
    // resolve(totalSize)：200 完整读取语义
    // ------------------------------------------------------------------

    @Test
    void fullReadResolvesToCompleteResponse() {
        ResourceRange.Resolved resolved = ResourceRange.fullRead().resolve(10L);

        assertThat(resolved.offset()).isZero();
        assertThat(resolved.length()).isEqualTo(10L);
        assertThat(resolved.partial()).isFalse();
    }

    @Test
    void fullReadOfEmptyObjectYieldsEmptyCompleteResponse() {
        ResourceRange.Resolved resolved = ResourceRange.fullRead().resolve(0L);

        assertThat(resolved.offset()).isZero();
        assertThat(resolved.length()).isZero();
        assertThat(resolved.partial()).isFalse();
    }

    // ------------------------------------------------------------------
    // resolve(totalSize)：206 部分读取语义
    // ------------------------------------------------------------------

    @Test
    void resolvesClosedRangeWithinBounds() {
        ResourceRange.Resolved resolved = ResourceRange.fromHeader("bytes=2-4").resolve(10L);

        assertThat(resolved.offset()).isEqualTo(2L);
        assertThat(resolved.length()).isEqualTo(3L);
        assertThat(resolved.partial()).isTrue();
    }

    @Test
    void clampsEndBeyondTotalSize() {
        ResourceRange.Resolved resolved = ResourceRange.fromHeader("bytes=8-99").resolve(10L);

        assertThat(resolved.offset()).isEqualTo(8L);
        assertThat(resolved.length()).isEqualTo(2L);
        assertThat(resolved.partial()).isTrue();
    }

    @Test
    void resolvesOpenEndedRange() {
        ResourceRange.Resolved resolved = ResourceRange.fromHeader("bytes=4-").resolve(10L);

        assertThat(resolved.offset()).isEqualTo(4L);
        assertThat(resolved.length()).isEqualTo(6L);
        assertThat(resolved.partial()).isTrue();
    }

    @Test
    void openEndedFromZeroCoversWholeObjectAsPartialContent() {
        ResourceRange.Resolved resolved = ResourceRange.fromHeader("bytes=0-").resolve(10L);

        assertThat(resolved.offset()).isZero();
        assertThat(resolved.length()).isEqualTo(10L);
        assertThat(resolved.partial()).isTrue();
    }

    @Test
    void resolvesSingleByteRange() {
        ResourceRange.Resolved resolved = ResourceRange.fromHeader("bytes=0-0").resolve(1L);

        assertThat(resolved.offset()).isZero();
        assertThat(resolved.length()).isEqualTo(1L);
        assertThat(resolved.partial()).isTrue();
    }

    @Test
    void resolvesSuffixRange() {
        ResourceRange.Resolved resolved = ResourceRange.fromHeader("bytes=-3").resolve(10L);

        assertThat(resolved.offset()).isEqualTo(7L);
        assertThat(resolved.length()).isEqualTo(3L);
        assertThat(resolved.partial()).isTrue();
    }

    @Test
    void suffixLongerThanTotalCoversWholeObject() {
        ResourceRange.Resolved resolved = ResourceRange.fromHeader("bytes=-99").resolve(10L);

        assertThat(resolved.offset()).isZero();
        assertThat(resolved.length()).isEqualTo(10L);
        assertThat(resolved.partial()).isTrue();
    }

    @Test
    void resolvesLastByteOfObject() {
        ResourceRange.Resolved resolved = ResourceRange.fromHeader("bytes=9-9").resolve(10L);

        assertThat(resolved.offset()).isEqualTo(9L);
        assertThat(resolved.length()).isEqualTo(1L);
        assertThat(resolved.partial()).isTrue();
    }

    // ------------------------------------------------------------------
    // resolve(totalSize)：416 不可满足语义
    // ------------------------------------------------------------------

    @Nested
    class UnsatisfiableRanges {

        @Test
        void startAtTotalIsUnsatisfiable() {
            assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=10-").resolve(10L))
                    .isInstanceOf(ResourceRangeException.class)
                    .satisfies(error -> {
                        ResourceRangeException rangeError = (ResourceRangeException) error;
                        assertThat(rangeError.reason()).isEqualTo(ResourceRangeException.Reason.UNSATISFIABLE);
                        assertThat(rangeError.totalSize()).isEqualTo(10L);
                    });
        }

        @Test
        void startBeyondTotalIsUnsatisfiable() {
            assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=25-30").resolve(10L))
                    .isInstanceOf(ResourceRangeException.class)
                    .satisfies(error -> assertThat(((ResourceRangeException) error).totalSize()).isEqualTo(10L));
        }

        @Test
        void suffixOfZeroIsUnsatisfiable() {
            assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=-0").resolve(10L))
                    .isInstanceOf(ResourceRangeException.class)
                    .satisfies(error -> {
                        ResourceRangeException rangeError = (ResourceRangeException) error;
                        assertThat(rangeError.reason()).isEqualTo(ResourceRangeException.Reason.UNSATISFIABLE);
                        assertThat(rangeError.totalSize()).isEqualTo(10L);
                    });
        }

        @Test
        void anyRangeOnEmptyObjectIsUnsatisfiable() {
            assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=0-0").resolve(0L))
                    .isInstanceOf(ResourceRangeException.class)
                    .satisfies(error -> assertThat(((ResourceRangeException) error).totalSize()).isZero());
            assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=-5").resolve(0L))
                    .isInstanceOf(ResourceRangeException.class);
        }

        @Test
        void unsatisfiableMessageCarriesNoStorageDetails() {
            assertThatThrownBy(() -> ResourceRange.fromHeader("bytes=40-50").resolve(10L))
                    .hasMessageNotContaining("resources/")
                    .hasMessageNotContaining("/");
        }
    }
}
