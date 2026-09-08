package com.h.backend.automation.infrastructure.scheduling;

/**
 * Spring 六段 Cron（秒 分 时 日 月 周）到 XXL-Job/Quartz 方言的转换。
 * Quartz 规则：日（day-of-month）与周（day-of-week）不能同时指定，
 * 未指定的一方必须写 '?'；Spring 双方都用 '*'，因此需要按语义改写。
 */
public final class XxlCronExpression {

    private XxlCronExpression() {
    }

    public static String fromSpring(String springCron) {
        if (springCron == null || springCron.isBlank()) {
            throw new IllegalArgumentException("Cron 表达式不能为空");
        }
        String[] fields = springCron.trim().split("\\s+");
        if (fields.length != 6) {
            throw new IllegalArgumentException("Spring Cron 必须为 6 段（秒 分 时 日 月 周）：" + springCron);
        }
        String second = fields[0];
        String minute = fields[1];
        String hour = fields[2];
        String dayOfMonth = fields[3];
        String month = fields[4];
        String dayOfWeek = fields[5];

        boolean domWildcard = "*".equals(dayOfMonth) || "?".equals(dayOfMonth);
        boolean dowWildcard = "*".equals(dayOfWeek) || "?".equals(dayOfWeek);
        if (!domWildcard && !dowWildcard) {
            throw new IllegalArgumentException(
                    "Quartz 不允许日与周同时指定（其中一个必须为 * 或 ?）：" + springCron);
        }
        String quartzDom = domWildcard ? (dowWildcard ? "*" : "?") : dayOfMonth;
        String quartzDow = dowWildcard ? "?" : dayOfWeek;
        return String.join(" ", second, minute, hour, quartzDom, month, quartzDow);
    }
}
