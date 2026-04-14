package io.github.raipc.metrics.jfr;

import java.io.InputStream;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.github.raipc.metrics.util.NumRemovingThreadPoolNameExtractor;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JfrAllocationStallTest {

    // Run with VM args: -XX:+UseZGC -Xmx1G
    @Test
    void allocationStall() throws Exception {
        Assumptions.assumeTrue(JfrAllocationStall.isApplicable(), "ZGC must be used");

        var reader = InMemoryMetricReader.create();
        var meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        var meter = meterProvider.get("test");

        try (var rs = new RecordingStream()) {
            rs.setReuse(true);
            rs.setOrdered(true);

            new JfrAllocationStall(meter, new NumRemovingThreadPoolNameExtractor()).subscribe(rs);

            var consumer = new JfrEventConsumerStub();
            rs.onEvent(consumer);
            rs.startAsync();

            try (InputStream is = InputStream.nullInputStream()) {
                while (consumer.events.isEmpty()) {
                    is.read(new byte[10 << 25]);
                }
            }

            var metrics = reader.collectAllMetrics();
            var metricNames = metrics.stream()
                    .map(m -> m.getName())
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(metricNames).contains("jvm.gc.allocation_stall");
        }
    }
}
