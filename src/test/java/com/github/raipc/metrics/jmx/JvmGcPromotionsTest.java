package com.github.raipc.metrics.jmx;

import java.util.stream.Collectors;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JvmGcPromotionsTest {

    @Test
    void collectsPromotionAfterGc() throws Exception {
        var reader = InMemoryMetricReader.create();
        var meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        var meter = meterProvider.get("test");

        try (var promotions = new JvmGcPromotions(meter)) {
            @SuppressWarnings("unused") var garbage = new byte[1 << 20];
            System.gc();
            garbage = new byte[1 << 20];
            System.gc();

            // GC notifications are delivered asynchronously
            Thread.sleep(500);

            var metrics = reader.collectAllMetrics();
            var metricNames = metrics.stream()
                    .map(MetricData::getName)
                    .collect(Collectors.toSet());

            assertThat(metricNames).contains("jvm.gc.promotion");
        }
    }

    @Test
    void closeRemovesListeners() {
        var reader = InMemoryMetricReader.create();
        var meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        var meter = meterProvider.get("test");

        var allocations = new JvmGcPromotions(meter);
        allocations.close();
    }
}
