package com.h.backend.automation.domain;

import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Cron 与时区共同组成调度规则；所有数据库时间统一保存为 UTC。 */
public record AutomationSchedule(String cronExpression, String zoneId) {

    private static final Duration MIN_INTERVAL = Duration.ofMinutes(1);

    public AutomationSchedule {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new IllegalArgumentException("Cron 表达式不能为空");
        }
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("时区不能为空");
        }
        cronExpression = cronExpression.trim();
        try {
            zoneId = ZoneId.of(zoneId.trim()).getId();
        } catch (DateTimeException error) {
            throw new IllegalArgumentException("无效时区：" + zoneId, error);
        }
        CronExpression cron = CronExpression.parse(cronExpression);
        ZonedDateTime first = cron.next(ZonedDateTime.now(ZoneId.of(zoneId)));
        ZonedDateTime second = first == null ? null : cron.next(first);
        if (first == null || second == null) {
            throw new IllegalArgumentException("Cron 表达式没有可执行的未来时间");
        }
        if (Duration.between(first.toInstant(), second.toInstant()).compareTo(MIN_INTERVAL) < 0) {
            throw new IllegalArgumentException("自动化任务执行间隔不能短于 1 分钟");
        }
    }

    public Instant nextAfter(Instant instant) {
        CronExpression cron = CronExpression.parse(cronExpression);
        ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(instant, ZoneId.of(zoneId)));
        if (next == null) {
            throw new IllegalStateException("Cron 表达式没有下一次执行时间");
        }
        return next.toInstant();
    }
}
