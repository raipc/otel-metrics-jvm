package com.github.raipc.metrics.jfr;

import java.util.stream.Collectors;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JfrGarbageCollectionTest {

    @Test
    void fullGc() {
        var reader = InMemoryMetricReader.create();
        var meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        var meter = meterProvider.get("test");

        try (var rs = new RecordingStream()) {
            rs.setReuse(true);
            rs.setOrdered(true);

            new JfrGarbageCollection(meter).subscribe(rs);

            var consumer = new JfrEventConsumerStub();
            rs.onEvent(consumer);
            rs.startAsync();

            System.gc();
            System.gc();
            consumer.await("System.gc()");

            var metrics = reader.collectAllMetrics();
            var metricNames = metrics.stream()
                    .map(MetricData::getName)
                    .collect(Collectors.toSet());

            assertThat(metricNames).contains(
                    "jvm.gc.pause",
                    "jvm.gc.longest_pause"
            );

            var metricsString = metrics.toString();
            assertThat(metricsString).contains("System.gc()");
        }
    }
}
