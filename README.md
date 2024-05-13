# natchez-server-logger-repro-example

Example repo to demonstrate http4s Server Logger middleware
is incompatible with loggers using IOLocal context, e.g. otel4s or natchez trace
context

To run:

```
env $(cat local.env) sbt run
```

## Issue

We can see that natchez-log and the implementation of `TracedLogger` match up,
with log statements in the app including correct trace IDs. The Client Logger
also correctly applies trace IDs in the mdc, using the same `logAction`

With the client we see that order of application of tracing middleware and
logging middleware matters to whether trace headers appear in the logged request headers, but not to whether they appear in the log statement's MDC.

However the server logger does not apply any trace context, regardless of any
order of application of middleware.

Log output:
```
[info] running (fork) example.Main
[info] {"timestamp":"2024-05-13T12:54:24.542Z","level":"INFO","thread":"io-compute-5","logger":"org.http4s.ember.server.EmberServerBuilderCompanionPlatform","message":"Ember-Server service bound to address: 127.0.0.1:8080","context":"default"}
[info] {"timestamp":"2024-05-13T12:54:26.466Z","level":"INFO","thread":"io-compute-0","mdc":{"span_id":"44402ec79efe5051","trace_id":"d756e153bf92eb0bc9f70e84a83f4364"},"logger":"example.Main","message":"log message","context":"default"}
[info] {"timestamp":"2024-05-13T12:54:26.520Z","level":"INFO","thread":"io-compute-0","mdc":{"span_id":"44402ec79efe5051","trace_id":"d756e153bf92eb0bc9f70e84a83f4364"},"logger":"example.Main","message":"HTTP/1.1 200 OK Headers(Content-Type: text/plain; charset=UTF-8, Content-Length: 7) body=\"\"","context":"default"}
[info] {"timestamp":"2024-05-13T12:54:26.523Z","level":"INFO","thread":"io-compute-9","mdc":{"span_id":"44402ec79efe5051","trace_id":"d756e153bf92eb0bc9f70e84a83f4364"},"logger":"example.Main","message":"HTTP/1.1 GET /downstream Headers() body=\"\"","context":"default"}
[info] {"timestamp":"2024-05-13T12:54:26.569Z","level":"INFO","thread":"io-compute-3","logger":"example.Main","message":"HTTP/1.1 GET /operation Headers(Host: localhost:8080, User-Agent: curl/8.4.0, Accept: */*) body=\"\"","context":"default"}
[info] {"timestamp":"2024-05-13T12:54:26.573Z","level":"INFO","thread":"io-compute-3","logger":"example.Main","message":"HTTP/1.1 200 OK Headers(Content-Type: text/plain; charset=UTF-8, Content-Length: 7) body=\"success\"","context":"default"}
```

## Desired Outcome

When creating the `http4s-server-request` span, the logged request body message should
include the Trace ID and Span ID in the MDC

