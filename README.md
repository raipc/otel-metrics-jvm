# otel-metrics-jvm

Небольшая библиотека для экспорта JVM-метрик в OpenTelemetry (JMX, JFR, hiccup-meter) для анализа производительности сборщика мусора.

## Метрики

### JMX: `JvmGc`

| Имя | Тип | Единица | Описание |
|-----|-----|---------|----------|
| `jvm.gc.count` | Counter | 1 | Суммарное число сборок по каждому коллектору (`GarbageCollectorMXBean`). |
| `jvm.gc.time` | Counter | ms | Суммарное время сборок по каждому коллектору. |

Атрибуты: `jvm.gc.name`, `instrumentation.source=jmx`.

### JMX: `JvmGcPromotions`

| Имя | Тип | Единица | Описание                                                                                                                  |
|-----|-----|---------|---------------------------------------------------------------------------------------------------------------------------|
| `jvm.gc.promotion` | Counter | By | Апроксимированный объём данных, перемещённых из молодого поколения в старое, по событиям GC (Parallel, G1, GenShen, ZGC). |

### JMX: `JvmThreadsCpu`

| Имя | Тип | Единица | Описание |
|-----|-----|---------|----------|
| `jvm.thread.cpu_time` | Counter | ns | Накопленное CPU-время по пулу (`thread.pool.name`). |
| `jvm.thread.cpu_time.total` | Counter | ns | То же по всем пулам (без `thread.pool.name`). |
| `jvm.thread.count` | Gauge | 1 | Число потоков по пулу. |
| `jvm.thread.count.total` | Gauge | 1 | Число потоков по всем пулам. |
| `jvm.thread.allocated_bytes` | Counter | By | Накопленные аллокации по пулу (только для Java-потоков, где доступен учёт). |
| `jvm.thread.allocated_bytes.total` | Counter | By | Накопленные аллокации по всем пулам. |
| `jvm.gc.thread.cpu_time` | Counter | ns | Накопленное CPU-время GC-потоков (если включена агрегация по внутренним VM-потокам). |
| `jvm.gc.thread.count` | Gauge | 1 | Число GC-потоков (при той же опции). |

Атрибут `thread.pool.name` — только у метрик без суффикса `.total`.

### JFR: `JfrGarbageCollection`

| Имя | Тип | Единица | Описание |
|-----|-----|---------|----------|
| `jvm.gc.pause` | Histogram | µs | Сумма пауз внутри одного события GC (`jdk.GarbageCollection`). |
| `jvm.gc.longest_pause` | Histogram | µs | Самая длинная пауза в событии. |

Атрибуты: `jvm.gc.name`, `jvm.gc.cause`, `instrumentation.source=jfr`.  

### JFR: `JfrAllocationStall` (ZGC)

| Имя | Тип | Единица | Описание |
|-----|-----|---------|----------|
| `jvm.gc.allocation_stall` | Histogram | us | Длительность allocation stall (`jdk.ZAllocationStall`). |

Атрибуты: `thread.pool.name`, `instrumentation.source=jfr`.

### Hiccups: `JvmHiccups`

| Имя | Тип | Единица | Описание               |
|-----|-----|---------|------------------------|
| `jvm.hiccup.duration` | Histogram | us | Платформенные хиккапы. |

## JVM-опции для метрик по внутренним (VM) потокам

Чтобы `JvmThreadsCpu` мог читать CPU внутренних потоков HotSpot через `HotspotThreadMBean` (агрегация GC-потоков и/или учёт внутренних потоков в пулах), процесс должен быть запущен с открытием пакета:

```text
--add-opens=java.management/sun.management=ALL-UNNAMED
```

Без этой опции загрузка `getInternalThreadCpuTimes` не удаётся, и метрики `jvm.gc.thread.*` при включённой агрегации GC, а также распределение внутренних VM-потоков по пулам, будут недоступны (в лог пишется предупреждение).

Дополнительно для **Java**-потоков:

- **CPU по потоку** — если `ThreadMXBean.getThreadCpuTime` возвращает `-1`, включите учёт: `-XX:+ThreadCpuTime` (в многих сборках HotSpot уже включён).
- **Аллокации по потоку** — нужен `com.sun.management.ThreadMXBean`; при отсутствии поддержки или отключённом учёте аллокации не попадут в `jvm.thread.allocated_bytes`. При необходимости: `-XX:+EnableThreadAllocatedMemory` (в актуальных JDK включено по умолчанию).

JFR-стрим (`JfrMetrics`, обработчики `JfrGarbageCollection`, `JfrAllocationStall`) опирается на встроенный в JDK механизм JFR; для continuous streaming обычно достаточно стандартной конфигурации JDK 17+. JFR стримы пишутся на диск и читаются с него, потому в случае медленного или нестбильного диска рассмотрите возможность отключить метрики JFR или перенести запись JFR стрима (по умолчанию в TMPDIR) на RAM диск.

## Пример подключения (Spring Boot)
В контексте должен лежать бин OpenTelemetry. Настроить его можно через добавление `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter`.

```java
@Configuration(proxyBeanMethods = false)
class MetricsConfiguration {
	@Bean
	ThreadPoolNameExtractor threadPoolNameExtractor() {
		return new NumRemovingThreadPoolNameExtractor();
	}

	@Bean(initMethod = "start")
	JfrMetrics jfrMetrics(OpenTelemetry openTelemetry, ThreadPoolNameExtractor extractor) {
		Meter meter = openTelemetry.getMeter("jvm-metrics-ext");
		List<JfrEventHandler> handlers = JfrAllocationStall.isApplicable()
				? List.of(new JfrGarbageCollection(meter), new JfrAllocationStall(meter, extractor))
				: List.of(new JfrGarbageCollection(meter));
		return new JfrMetrics(handlers);
	}

	@Bean
	JvmThreadsCpu jvmThreadsCpu(OpenTelemetry openTelemetry, ThreadPoolNameExtractor extractor) {
		Meter meter = openTelemetry.getMeter("jvm-metrics-ext");
		return new JvmThreadsCpu(meter, extractor);
	}

	@Bean
	JvmHiccups jvmHiccups(OpenTelemetry openTelemetry) {
		Meter meter = openTelemetry.getMeter("jvm-metrics-ext");
		return new JvmHiccups(meter);
	}

    @Bean
    JvmGcPromotions jvmGcPromotions(OpenTelemetry openTelemetry) {
        Meter meter = openTelemetry.getMeter("jvm-metrics-ext");
        return new JvmGcPromotions(meter);
    }

    @Bean
    JvmGc jvmGc(OpenTelemetry openTelemetry) {
        Meter meter = openTelemetry.getMeter("jvm-metrics-ext");
        return new JvmGc(meter);
    }
}
```