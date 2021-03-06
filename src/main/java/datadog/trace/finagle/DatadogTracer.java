package datadog.trace.finagle;

import com.google.auto.service.AutoService;
import com.twitter.finagle.tracing.Record;
import com.twitter.finagle.tracing.SpanId;
import com.twitter.finagle.tracing.TraceId;
import com.twitter.finagle.tracing.Tracer;
import datadog.trace.api.Config;
import java.io.Closeable;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

@AutoService(Tracer.class)
public class DatadogTracer implements Tracer, Closeable {
  private static final Logger log = LoggerFactory.getLogger(DatadogTracer.class);

  private static final long FLUSH_PERIOD = TimeUnit.SECONDS.toMillis(1);
  private static final int FLUSHED_TRACES_CACHE_SIZE = 500;

  // Finagle sometimes sends records after a trace was completed.  This results in the new partial
  // trace overriding the old data.  The cache keeps a list of ids that were already complete to
  // ignore the late records
  private final LRUCache<BigInteger> flushedSpans = new LRUCache<>(FLUSHED_TRACES_CACHE_SIZE);

  private final Map<SpanId, PendingTrace> traces = new ConcurrentHashMap<>();

  private final ScheduledExecutorService executorService;
  private final DDApi ddApi;
  private final String serviceName;

  public DatadogTracer() {
    this(Config.get().getServiceName(), Config.get().getAgentHost(), Config.get().getAgentPort());
  }

  public DatadogTracer(String serviceName, String agentHost, int port) {
    executorService =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r, "dd-tracer-flush");
              thread.setDaemon(true);
              return thread;
            });
    executorService.scheduleAtFixedRate(
        this::flush, FLUSH_PERIOD, FLUSH_PERIOD, TimeUnit.MILLISECONDS);

    this.ddApi = new DDApi(agentHost, port);
    this.serviceName = serviceName;

    // Double configSampleRate = datadog.trace.api.Config.get().getTraceSampleRate()
  }

  @Override
  public void record(Record record) {
    log.debug("Record {}", record);
    PendingTrace pendingTrace =
        traces.computeIfAbsent(
            record.traceId().traceId(),
            (key) -> {
              BigInteger spanId = new BigInteger(record.traceId().spanId().toString(), 16);
              if (!flushedSpans.contains(spanId)) {
                log.debug("Starting new trace {}", key);
                return new PendingTrace(serviceName);
              } else {
                log.debug("Received record for already reported span {}", record);
                return null;
              }
            });

    if (pendingTrace != null) {
      pendingTrace.addRecord(record);

      if (pendingTrace.isComplete()) {
        addSpansToFlushed(pendingTrace);
        traces.remove(record.traceId().traceId());
        ddApi.sendTrace(pendingTrace);
      }
    }
  }

  private void addSpansToFlushed(PendingTrace pendingTrace) {
    for (Span span : pendingTrace.getSpans()) {
      flushedSpans.add(span.getSpanId());
    }
  }

  @Override
  public boolean isNull() {
    return false;
  }

  @Override
  public Option<Object> sampleTrace(TraceId traceId) {
    // TODO implement sampling
    return Tracer.SomeTrue();
  }

  @Override
  public boolean isActivelyTracing(TraceId traceId) {
    return traceId.getSampled().getOrElse(() -> true);
  }

  @Override
  public void close() {
    executorService.shutdownNow();
    ddApi.close();
  }

  private void flush() {
    final Iterator<Map.Entry<SpanId, PendingTrace>> iterator = traces.entrySet().iterator();

    while (iterator.hasNext()) {
      final Map.Entry<SpanId, PendingTrace> next = iterator.next();
      if (next.getValue().isComplete()) {
        addSpansToFlushed(next.getValue());
        iterator.remove();
        ddApi.sendTrace(next.getValue());
      }
    }
  }
}
