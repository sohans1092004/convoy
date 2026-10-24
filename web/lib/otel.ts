import { context, propagation, SpanKind, trace, type Span } from '@opentelemetry/api';
import { W3CTraceContextPropagator } from '@opentelemetry/core';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { BasicTracerProvider, BatchSpanProcessor } from '@opentelemetry/sdk-trace-base';

// Server-side tracing for the BFF: one span per proxied request, exported to the OpenTelemetry
// Collector, with W3C traceparent forwarded to the gateway so a trace starts at the web tier.
let started = false;

function start() {
  if (started) return;
  started = true;
  const endpoint = process.env.OTEL_EXPORTER_OTLP_ENDPOINT;
  if (!endpoint) return; // no collector configured: tracing calls are no-ops
  const provider = new BasicTracerProvider({
    resource: resourceFromAttributes({ 'service.name': process.env.OTEL_SERVICE_NAME || 'web' }),
    spanProcessors: [new BatchSpanProcessor(new OTLPTraceExporter({ url: `${endpoint}/v1/traces` }))],
  });
  trace.setGlobalTracerProvider(provider);
  propagation.setGlobalPropagator(new W3CTraceContextPropagator());
}

/** Run fn inside a SERVER span named after the BFF route; returns fn's result and the outgoing traceparent headers. */
export async function withBffSpan<T>(name: string, fn: (headers: Record<string, string>, span: Span) => Promise<T>): Promise<T> {
  start();
  const tracer = trace.getTracer('convoy-web');
  const span = tracer.startSpan(name, { kind: SpanKind.SERVER });
  {
    // No async context manager is registered, so put the span into the context explicitly before injecting.
    const carrier: Record<string, string> = {};
    propagation.inject(trace.setSpan(context.active(), span), carrier);
    try {
      return await fn(carrier, span);
    } finally {
      span.end();
    }
  }
}
