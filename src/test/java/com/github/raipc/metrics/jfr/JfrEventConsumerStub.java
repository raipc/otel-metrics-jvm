package com.github.raipc.metrics.jfr;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import jdk.jfr.consumer.RecordedEvent;

class JfrEventConsumerStub implements Consumer<RecordedEvent> {
    final ArrayBlockingQueue<String> events = new ArrayBlockingQueue<>(10000);

    @Override
    public void accept(RecordedEvent recordedEvent) {
        var event = recordedEvent.toString();
        System.out.println(event);
        events.add(event);
    }

    String await(String... substr) {
        return await(Duration.ofSeconds(10), substr);
    }

    String await(Duration duration, String... substr) {
        try {
            String result;
            boolean match;
            long until = System.nanoTime() + duration.toNanos();
            do {
                result = events.poll(Math.max(until - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
                if (result == null) {
                    throw new TimeoutException();
                }
                match = true;
                for (var s : substr) {
                    if (!result.contains(s)) {
                        match = false;
                        break;
                    }
                }
            } while (!match);
            return result;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
