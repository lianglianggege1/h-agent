package com.h.backend.automation.domain;

import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

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

    /** 从 instant（不含）开始的未来 n 次合法触发时刻，用于确认界面展示。 */
    public java.util.List<Instant> upcomingFires(Instant instant, int count) {
        CronExpression cron = CronExpression.parse(cronExpression);
        ZoneId zone = ZoneId.of(zoneId);
        ZonedDateTime cursor = ZonedDateTime.ofInstant(instant, zone);
        java.util.List<Instant> fires = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            cursor = cron.next(cursor);
            if (cursor == null) {
                break;
            }
            fires.add(cursor.toInstant());
        }
        return fires;
    }

    /**
     * 把外部触发的观测时刻归一化到「不晚于观测时刻、且落在 misfire 容忍窗口内」的最近一个
     * 合法日程点。重复回调、延迟回调都会收敛到同一个逻辑时刻（即同一个幂等键）；
     * 窗口内没有日程点时返回 empty，调用方按 SKIPPED_MISFIRE 处理。
     */
    public Optional<Instant> latestFireWithin(Instant observed, Duration misfireWindow) {
        CronExpression cron = CronExpression.parse(cronExpression);
        ZoneId zone = ZoneId.of(zoneId);
        ZonedDateTime obs = ZonedDateTime.ofInstant(observed, zone);
        ZonedDateTime windowStart = obs.minus(misfireWindow).minusSeconds(1);
        ZonedDateTime candidate = cron.next(windowStart);
        ZonedDateTime latest = null;
        int guard = 0;
        while (candidate != null && !candidate.isAfter(obs) && guard < 10_000) {
            latest = candidate;
            candidate = cron.next(candidate);
            guard++;
        }
        return latest == null ? Optional.empty() : Optional.of(latest.toInstant());
    }
}
