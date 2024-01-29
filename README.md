# natchez-server-logger-repro-example

Example repo to demonstrate http4s server Logger middleware
as incompatible with an otherwise effective logger that 
applies natchez trace context as log context

To run:

```
sbt 'app/run'
```

## Issue

We can see that natchez-log and the implementation of `TracedLogger` match up,
with log statements in the app including correct trace IDs. The Client Logger
also correctly applies trace IDs in the mdc, using the same `logAction`

With the client we see that order of application of tracing middleware and
logging middleware matters to whether trace headers appear in the logged request headers, but not to whether they appear in the log statement's MDC.

However the server logger does not apply any trace context, regardless of any
order of application of middleware.
