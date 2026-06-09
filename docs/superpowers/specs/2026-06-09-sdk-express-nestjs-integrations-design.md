# Callisto JS SDK — Express & NestJS error-capture integrations

**Date:** 2026-06-09
**Status:** Approved (design)
**Component:** `callisto-sdk/js`

## Goal

Add first-class Express and NestJS integrations to `callisto-sdk` (JS/TS) that
automatically report unhandled request errors through the SDK's existing
`ErrorReporter`. The integrations are **error-capture only** — they observe and
report, then re-throw / pass the error along. They never alter the host app's
HTTP responses, error handling, or control flow.

## Non-goals (explicitly out of scope)

- **No DI / provider wiring.** No `CallistoModule.forRoot()`, no Express
  factory for constructing/injecting `CallistoClient`. The host constructs its
  own client and hands it to the integration.
- **No request-context enrichment beyond method + path.** No user id, route
  params, query, headers, or body capture.
- **No changes to the messaging client** (`sms`/`otp`/`whatsapp`/`notify`/`balance`).

## Confirmed decisions

| Decision | Choice |
| --- | --- |
| Surface covered | Error capture only |
| Packaging | Subpath exports (`@callisto/sdk/express`, `@callisto/sdk/nestjs`) |
| Default capture policy | **All errors**, overridable via `shouldReport(err)` |
| Pass-through behavior | Always re-throw / `next(err)` — responses never altered |
| NestJS mechanism | `NestInterceptor` with rxjs `tap({ error })` (not an `ExceptionFilter`) |
| Context attached | `http_method` + `http_path` only (no PII) |

## Packaging

Both integrations ship as subpath exports of the existing single package so the
core import stays dependency-free:

- `@callisto/sdk/express` → `dist/integrations/express.js`
- `@callisto/sdk/nestjs` → `dist/integrations/nestjs.js`

`package.json` changes:

- Add `exports` entries:
  ```jsonc
  "./express": { "types": "./dist/integrations/express.d.ts", "import": "./dist/integrations/express.js" },
  "./nestjs":  { "types": "./dist/integrations/nestjs.d.ts",  "import": "./dist/integrations/nestjs.js" }
  ```
- Add optional peer dependencies (so plain consumers install nothing extra):
  ```jsonc
  "peerDependencies":     { "express": ">=4", "@nestjs/common": ">=9", "rxjs": ">=7" },
  "peerDependenciesMeta": { "express": { "optional": true }, "@nestjs/common": { "optional": true }, "rxjs": { "optional": true } }
  ```
- Add matching devDependencies (`express`, `@types/express`, `@nestjs/common`,
  `rxjs`) for building and testing.

`tsconfig.json` already compiles `src/**` → `dist/**` preserving directory
structure, so the new `src/integrations/*.ts` files emit automatically.

Framework types are imported **`type`-only** (`import type { Request } from
"express"`, `import type { NestInterceptor } from "@nestjs/common"`). The only
runtime framework import is rxjs `{ tap }` in the NestJS file — rxjs is always
present in a Nest app.

## Components

### `src/integrations/shared.ts` (no framework imports)

The common glue, depending only on the SDK's own `captureException` surface.

```ts
/** Minimal surface the integrations need from a CallistoClient. */
export interface ErrorCapturer {
  captureException(
    error: unknown,
    level?: string,
    extra?: Record<string, unknown> | null,
  ): void;
}

export interface CallistoIntegrationOptions {
  /** Return false to skip reporting a given error. Default: report everything. */
  shouldReport?(err: unknown): boolean;
  /** Level for reported events. Default: "error". */
  level?: string | ((err: unknown) => string);
}

export interface RequestInfo {
  method: string;
  path: string;
}

/**
 * Decide-and-report. Never throws. Attaches request method/path as extra
 * (http_method / http_path) — deliberately no body/headers/query/PII.
 */
export function reportRequestError(
  client: ErrorCapturer,
  err: unknown,
  req: RequestInfo,
  options?: CallistoIntegrationOptions,
): void;
```

Behavior:
- If `options.shouldReport` is set and returns falsy, do nothing.
- Resolve level: call it if a function, else use the string, else `"error"`.
- Call `client.captureException(err, level, { http_method, http_path })`.
- Wrap in try/catch so a reporting failure never propagates.

### `src/integrations/express.ts` → `@callisto/sdk/express`

```ts
import type { ErrorRequestHandler } from "express";

export function callistoErrorHandler(
  client: ErrorCapturer,
  options?: CallistoIntegrationOptions,
): ErrorRequestHandler;
```

- Returns a 4-arg `(err, req, res, next)` Express **error-handling middleware**
  (the 4-arg signature is what makes Express treat it as error middleware).
- On invocation: build `RequestInfo` as
  `{ method: req.method, path: req.route?.path ?? req.originalUrl ?? req.url }`,
  call `reportRequestError(...)`, then **always** `next(err)`.
- Documented usage: register last, immediately before the host's own error
  handler.

### `src/integrations/nestjs.ts` → `@callisto/sdk/nestjs`

```ts
import type { NestInterceptor, ExecutionContext, CallHandler } from "@nestjs/common";
import type { Observable } from "rxjs";
import { tap } from "rxjs";

export class CallistoInterceptor implements NestInterceptor {
  constructor(client: ErrorCapturer, options?: CallistoIntegrationOptions);
  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown>;
}
```

- `intercept` returns `next.handle().pipe(tap({ error: (err) => report(...) }))`.
  `tap` observes the error and re-emits it untouched, so response shaping and
  any downstream exception filters are unaffected.
- Request info pulled from `context.switchToHttp().getRequest()`:
  `{ method: req.method, path: req.route?.path ?? req.originalUrl ?? req.url }`.
  Guard for non-HTTP contexts (e.g. RPC/WS) — if no HTTP request is available,
  report with `path` omitted/empty rather than throwing.
- Registered by the host via
  `app.useGlobalInterceptors(new CallistoInterceptor(client))` or an
  `APP_INTERCEPTOR` provider. No module is shipped.

**Why an interceptor, not an `ExceptionFilter`:** a global `@Catch()` filter
takes over the HTTP response and would require re-delegating to
`BaseExceptionFilter` to avoid breaking responses — fragile and coupling-heavy.
An interceptor with `tap` captures and passes through with zero response impact,
matching the "never alter responses" requirement.

## Public API additions

Nothing is added to the package root (`@callisto/sdk`). The integrations are
only reachable through their subpaths. `ErrorCapturer`,
`CallistoIntegrationOptions`, `callistoErrorHandler`, and `CallistoInterceptor`
are exported from their respective subpath modules.

## Testing (vitest, matching existing `test/` style)

`test/integrations/express.test.ts`:
- Fake `req` (`method`, `route.path`, `originalUrl`), `res`, and a `next` spy
  plus a fake client capturing `captureException` calls.
- Asserts: error reported with correct `http_method`/`http_path` and resolved
  level; `next(err)` always called with the original error.
- Asserts: `shouldReport: () => false` suppresses the report but still calls
  `next(err)`.
- Asserts: a throwing `captureException` does not prevent `next(err)`.

`test/integrations/nestjs.test.ts`:
- Fake `ExecutionContext` (`switchToHttp().getRequest()` → fake req) and a
  `CallHandler` whose `handle()` returns an erroring observable.
- Asserts: subscribing propagates the original error to the subscriber AND the
  client reported it once with correct context.
- Asserts: `shouldReport` gating; success path (no error) reports nothing.
- Asserts: non-HTTP context does not throw.

## Documentation

Add a "Framework integrations" section to `README.md` with short Express and
NestJS usage snippets and the `CallistoIntegrationOptions` table.
