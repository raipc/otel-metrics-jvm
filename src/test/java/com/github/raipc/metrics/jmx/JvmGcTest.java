package com.github.raipc.metrics.jmx;

import java.util.stream.Collectors;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JvmGcTest {

    @Test
    void registersMxBeanMetrics() {
        var reader = InMemoryMetricReader.create();
        var meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        var meter = meterProvider.get("test");

        try (var gc = new JvmGc(meter)) {
            reader.collectAllMetrics();
            var metrics = reader.collectAllMetrics();
            var metricNames = metrics.stream()
                    .map(MetricData::getName)
                    .collect(Collectors.toSet());

            assertThat(metricNames).contains("jvm.gc.count", "jvm.gc.time");

            var attrsDump = metrics.stream()
                    .map(MetricData::toString)
                    .collect(Collectors.joining("\n"));
            assertThat(attrsDump).contains("instrumentation.source");
            assertThat(attrsDump).contains("jmx");
        }
    }
}
