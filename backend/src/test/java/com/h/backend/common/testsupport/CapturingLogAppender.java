package com.h.backend.common.testsupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * log4j2 事件收集 Appender（替代 logback 的 ListAppender）。
 * 用法：try (var attached = CapturingLogAppender.attach(Xxx.class)) { ... attached.events() ... }
 */
public final class CapturingLogAppender extends AbstractAppender {

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    private CapturingLogAppender(String name) {
        super(name, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
        // 同步路径下事件对象可能被复用，必须冻结快照
        events.add(event.toImmutable());
    }

    public List<LogEvent> events() {
        return events;
    }

    public static Attached attach(Class<?> loggerClass) {
        Logger logger = (Logger) LogManager.getLogger(loggerClass);
        CapturingLogAppender appender = new CapturingLogAppender(
                "capturing-" + loggerClass.getSimpleName() + "-" + System.nanoTime());
        appender.start();
        logger.addAppender(appender);
        return new Attached(logger, appender);
    }

    public record Attached(Logger logger, CapturingLogAppender appender) implements AutoCloseable {
        @Override
        public void close() {
            logger.removeAppender(appender);
            appender.stop();
        }
    }
}
