package com.h.backend.chat.infrastructure.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Configuration
@EnableConfigurationProperties(LangfuseTelemetryProperties.class)
public class LangfuseTelemetryConfig {

    @Bean
    public OpenTelemetry openTelemetry(LangfuseTelemetryProperties properties) {
        if (!properties.enabled()
                || isBlank(properties.otlpEndpoint())
                || isBlank(properties.publicKey())
                || isBlank(properties.secretKey())) {
            return OpenTelemetry.noop();
        }

        String credentials = properties.publicKey() + ":" + properties.secretKey();
        String basicAuth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        OtlpHttpSpanExporter spanExporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(properties.otlpEndpoint())
                .addHeader("Authorization", "Basic " + basicAuth)
                .addHeader("x-langfuse-ingestion-version", "4")
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .setResource(Resource.getDefault())
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        return sdk;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
