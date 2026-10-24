package convoy.platform.messaging;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.HashMap;
import java.util.Map;

/**
 * W3C trace context across the outbox and SNS/SQS. The request's traceparent is stored on the outbox
 * row; the relay publishes inside a PRODUCER span parented by it and sends that span's traceparent as a
 * message attribute; consumers continue the trace in a CONSUMER span. Without the OpenTelemetry agent
 * every call here is a no-op.
 */
public final class Tracing {
    public static final String ATTR = "traceparent";
    private static final W3CTraceContextPropagator W3C = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> c) { return c.keySet(); }
        @Override public String get(Map<String, String> c, String k) { return c == null ? null : c.get(k); }
    };

    private Tracing() {}

    static Tracer tracer() { return GlobalOpenTelemetry.getTracer("convoy-platform"); }

    /** traceparent of the given context, or null when there is no valid span. */
    public static String traceparent(Context ctx) {
        Map<String, String> carrier = new HashMap<>();
        W3C.inject(ctx, carrier, Map::put);
        return carrier.get(ATTR);
    }

    public static String currentTraceparent() { return traceparent(Context.current()); }

    public static Context parent(String traceparent) {
        if (traceparent == null) return Context.root();
        return W3C.extract(Context.root(), Map.of(ATTR, traceparent), GETTER);
    }

    public static Span start(String name, SpanKind kind, String parentTraceparent) {
        return tracer().spanBuilder(name).setSpanKind(kind).setParent(parent(parentTraceparent)).startSpan();
    }
}
