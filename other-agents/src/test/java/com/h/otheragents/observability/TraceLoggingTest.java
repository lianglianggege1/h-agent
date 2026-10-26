package com.h.otheragents.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.AppenderAttachable;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class TraceLoggingTest {
    private LoggerContext context;
    private Logger logger;
    private ListAppender<ILoggingEvent> captured;
    private ConsoleAppender<ILoggingEvent> console;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void configureProductionLogging() throws Exception {
        context = new LoggerContext();
        context.setMDCAdapter(((LoggerContext) LoggerFactory.getILoggerFactory()).getMDCAdapter());
        var properties = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        context.putProperty("LOG_CORRELATION_PATTERN", (String) properties.getFirst().getProperty("logging.pattern.correlation"));
        var configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(getClass().getResource("/logback-spring.xml"));
        var root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        var bridge = (AppenderAttachable<ILoggingEvent>) root.getAppender("OTEL");
        assertThat(bridge).as("production logging must capture the OTel context").isNotNull();
        console = (ConsoleAppender<ILoggingEvent>) bridge.getAppender("CONSOLE");
        captured = new ListAppender<>();
        captured.setContext(context);
        captured.start();
        bridge.addAppender(captured);
        logger = context.getLogger(TraceLoggingTest.class);
    }

    @AfterEach
    void cleanup() {
        if (context != null) context.stop();
        MDC.clear();
    }

    @Test
    void logsCaptureTheActiveTraceAndSpanBeforeTheScopeIsClosed() {
        try (var provider = SdkTracerProvider.builder().build()) {
            Span span = provider.get("log-correlation-test").spanBuilder("business operation").startSpan();
            try (var scope = span.makeCurrent()) {
                logger.info("business log correlation probe");
            } finally {
                span.end();
            }
            var event = captured.list.getLast();
            assertThat(event.getMDCPropertyMap()).containsEntry("traceId", span.getSpanContext().getTraceId());
            assertThat(event.getMDCPropertyMap()).containsEntry("spanId", span.getSpanContext().getSpanId());
            // Encoding after the scope closes also covers deferred/async formatting.
            String line = new String(console.getEncoder().encode(event), StandardCharsets.UTF_8);
            assertThat(line).contains("[TraceId:" + span.getSpanContext().getTraceId() + "]")
                    .contains("[SpanId:" + span.getSpanContext().getSpanId() + "]");
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
            logger.info("background export log without a current span");
            var background = captured.list.getLast();
            assertThat(background.getMDCPropertyMap()).doesNotContainKeys("traceId", "spanId");
            assertThat(new String(console.getEncoder().encode(background), StandardCharsets.UTF_8))
                    .contains("[TraceId:-]").contains("[SpanId:-]");
        }
    }

    @Test
    void unsampledSpansKeepTheirIdsAndExistingRequestMdc() {
        MDC.put("requestId", "request-1");
        try (var provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOff()).build()) {
            Span span = provider.get("log-correlation-test").spanBuilder("unsampled operation").startSpan();
            try (var scope = span.makeCurrent()) {
                logger.info("unsampled business log");
            } finally {
                span.end();
            }
            assertThat(captured.list.getLast().getMDCPropertyMap())
                    .containsEntry("traceId", span.getSpanContext().getTraceId())
                    .containsEntry("spanId", span.getSpanContext().getSpanId())
                    .containsEntry("requestId", "request-1");
            assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys("requestId");
        }
    }

    @Test
    void springBootAppliesTheConfiguredCorrelationPattern(CapturedOutput output) {
        try (var application = new SpringApplicationBuilder(LoggingOnlyApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off", "--spring.output.ansi.enabled=never");
             var provider = SdkTracerProvider.builder().build()) {
            Span span = provider.get("log-correlation-test").spanBuilder("spring boot logging").startSpan();
            try (var scope = span.makeCurrent()) {
                LoggerFactory.getLogger(LoggingOnlyApplication.class).info("spring-boot-log-correlation-probe");
            } finally {
                span.end();
            }
            assertThat(output.getAll()).contains("[TraceId:" + span.getSpanContext().getTraceId() + "]")
                    .contains("[SpanId:" + span.getSpanContext().getSpanId() + "]");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LoggingOnlyApplication { }
}
