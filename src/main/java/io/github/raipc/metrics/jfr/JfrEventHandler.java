package io.github.raipc.metrics.jfr;

import java.util.function.Consumer;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

public interface JfrEventHandler extends Consumer<RecordedEvent> {
    void subscribe(RecordingStream stream);
}
