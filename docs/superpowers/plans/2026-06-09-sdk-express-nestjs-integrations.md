# Express & NestJS Error-Capture Integrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `@callisto/sdk/express` and `@callisto/sdk/nestjs` subpath integrations that auto-report unhandled request errors via the SDK's existing `ErrorReporter`, capture-only, never altering the host app's responses.

**Architecture:** A framework-agnostic `shared.ts` module owns the report decision + request-context shaping and depends only on a minimal `{ captureException }` surface (`ErrorCapturer`). Thin per-framework adapters (`express.ts`, `nestjs.ts`) extract `{ method, path }` and delegate to it. Express uses a 4-arg error middleware that always calls `next(err)`; NestJS uses a `NestInterceptor` with rxjs `tap({ error })` that re-emits the error untouched.

**Tech Stack:** TypeScript (ESM, `moduleResolution: Bundler`), vitest. Optional peers: `express`, `@nestjs/common`, `rxjs`. All framework types imported `type`-only; rxjs `tap` is the sole runtime framework import.

**Working directory:** All paths are relative to `callisto-sdk/js/` (the npm package). Git root is `callisto-sdk/`.

---

### Task 1: Install dev/peer dependencies and wire package exports

**Files:**
- Modify: `callisto-sdk/js/package.json`

- [ ] **Step 1: Install the framework packages as devDependencies**

Run (from `callisto-sdk/js/`):
```bash
npm install -D express @types/express @nestjs/common rxjs reflect-metadata
```
`reflect-metadata` and `rxjs` are runtime deps of `@nestjs/common`; installing them lets `tsc` resolve the type-only `@nestjs/common` import during build. `@types/express` provides the `express` types.

Expected: `package.json` `devDependencies` now lists `express`, `@types/express`, `@nestjs/common`, `rxjs`, `reflect-metadata`.

- [ ] **Step 2: Add subpath exports and optional peer dependencies**

Edit `callisto-sdk/js/package.json`. Replace the `"exports"` block so it reads:

```jsonc
"exports": {
  ".": {
    "types": "./dist/index.d.ts",
    "import": "./dist/index.js"
  },
  "./express": {
    "types": "./dist/integrations/express.d.ts",
    "import": "./dist/integrations/express.js"
  },
  "./nestjs": {
    "types": "./dist/integrations/nestjs.d.ts",
    "import": "./dist/integrations/nestjs.js"
  }
},
```

Then add these two top-level keys (anywhere after `"exports"`, before `"scripts"`):

```jsonc
"peerDependencies": {
  "express": ">=4",
  "@nestjs/common": ">=9",
  "rxjs": ">=7"
},
"peerDependenciesMeta": {
  "express": { "optional": true },
  "@nestjs/common": { "optional": true },
  "rxjs": { "optional": true }
},
```

- [ ] **Step 3: Verify the package.json is valid JSON and the build still works**

Run:
```bash
node -e "JSON.parse(require('fs').readFileSync('package.json','utf8')); console.log('ok')"
npm run build
```
Expected: prints `ok`, then `tsc` completes with no errors (no integration files exist yet — this just confirms nothing broke).

- [ ] **Step 4: Commit**

```bash
git add js/package.json js/package-lock.json
git commit -m "build: add express/nestjs peer deps and subpath exports"
```

---

### Task 2: Shared report logic (`shared.ts`)

**Files:**
- Create: `callisto-sdk/js/src/integrations/shared.ts`
- Test: `callisto-sdk/js/test/integrations/shared.test.ts`

- [ ] **Step 1: Write the failing test**

Create `callisto-sdk/js/test/integrations/shared.test.ts`:

```ts
import { describe, it, expect, vi } from "vitest";
import {
  reportRequestError,
  type ErrorCapturer,
} from "../../src/integrations/shared.js";

function fakeClient(): ErrorCapturer & {
  calls: Array<{ err: unknown; level?: string; extra?: unknown }>;
} {
  const calls: Array<{ err: unknown; level?: string; extra?: unknown }> = [];
  return {
    calls,
    captureException(err, level, extra) {
      calls.push({ err, level, extra });
    },
  };
}

describe("reportRequestError", () => {
  it("reports with http_method/http_path and default level 'error'", () => {
    const client = fakeClient();
    const err = new Error("boom");
    reportRequestError(client, err, { method: "GET", path: "/users/:id" });

    expect(client.calls).toHaveLength(1);
    expect(client.calls[0].err).toBe(err);
    expect(client.calls[0].level).toBe("error");
    expect(client.calls[0].extra).toEqual({
      http_method: "GET",
      http_path: "/users/:id",
    });
  });

  it("skips reporting when shouldReport returns false", () => {
    const client = fakeClient();
    reportRequestError(client, new Error("x"), { method: "GET", path: "/" }, {
      shouldReport: () => false,
    });
    expect(client.calls).toHaveLength(0);
  });

  it("resolves a string level and a function level", () => {
    const client = fakeClient();
    reportRequestError(client, new Error("a"), { method: "GET", path: "/" }, {
      level: "warning",
    });
    reportRequestError(client, new Error("b"), { method: "POST", path: "/" }, {
      level: () => "fatal",
    });
    expect(client.calls[0].level).toBe("warning");
    expect(client.calls[1].level).toBe("fatal");
  });

  it("never throws when captureException throws", () => {
    const client: ErrorCapturer = {
      captureException() {
        throw new Error("reporter exploded");
      },
    };
    expect(() =>
      reportRequestError(client, new Error("x"), { method: "GET", path: "/" }),
    ).not.toThrow();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run test/integrations/shared.test.ts`
Expected: FAIL — cannot resolve `../../src/integrations/shared.js`.

- [ ] **Step 3: Write the implementation**

Create `callisto-sdk/js/src/integrations/shared.ts`:

```ts
/** Minimal surface the integrations need from a CallistoClient. */
export interface ErrorCapturer {
  captureException(
    error: unknown,
    level?: string,
    extra?: Record<string, unknown> | null,
  ): void;
}

/** Common options for the Express and NestJS integrations. */
export interface CallistoIntegrationOptions {
  /** Return false to skip reporting a given error. Default: report everything. */
  shouldReport?(err: unknown): boolean;
  /** Level for reported events. Default: "error". */
  level?: string | ((err: unknown) => string);
}

/** Request metadata attached to a reported event. No body/headers/query. */
export interface RequestInfo {
  method: string;
  path: string;
}

function resolveLevel(
  level: CallistoIntegrationOptions["level"],
  err: unknown,
): string {
  if (typeof level === "function") return level(err);
  if (typeof level === "string") return level;
  return "error";
}

/**
 * Decide-and-report. Never throws. Attaches request method/path as extra
 * (`http_method` / `http_path`) — deliberately no body, headers, query, or PII.
 */
export function reportRequestError(
  client: ErrorCapturer,
  err: unknown,
  req: RequestInfo,
  options: CallistoIntegrationOptions = {},
): void {
  try {
    if (options.shouldReport && !options.shouldReport(err)) return;
    const level = resolveLevel(options.level, err);
    client.captureException(err, level, {
      http_method: req.method,
      http_path: req.path,
    });
  } catch {
    // Reporting must never disturb the host's error path.
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx vitest run test/integrations/shared.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add js/src/integrations/shared.ts js/test/integrations/shared.test.ts
git commit -m "feat: shared request-error report helper for integrations"
```

---

### Task 3: Express error-handler middleware (`express.ts`)

**Files:**
- Create: `callisto-sdk/js/src/integrations/express.ts`
- Test: `callisto-sdk/js/test/integrations/express.test.ts`

- [ ] **Step 1: Write the failing test**

Create `callisto-sdk/js/test/integrations/express.test.ts`:

```ts
import { describe, it, expect, vi } from "vitest";
import { callistoErrorHandler } from "../../src/integrations/express.js";
import type { ErrorCapturer } from "../../src/integrations/shared.js";

function fakeClient(): ErrorCapturer & {
  calls: Array<{ err: unknown; level?: string; extra?: unknown }>;
} {
  const calls: Array<{ err: unknown; level?: string; extra?: unknown }> = [];
  return {
    calls,
    captureException(err, level, extra) {
      calls.push({ err, level, extra });
    },
  };
}

// Express recognizes error middleware by its 4-arg arity.
function isErrorMiddleware(fn: Function): boolean {
  return fn.length === 4;
}

describe("callistoErrorHandler", () => {
  it("returns a 4-arg express error middleware", () => {
    const handler = callistoErrorHandler(fakeClient());
    expect(isErrorMiddleware(handler)).toBe(true);
  });

  it("reports with route path and method, then calls next(err)", () => {
    const client = fakeClient();
    const handler = callistoErrorHandler(client);
    const err = new Error("boom");
    const next = vi.fn();

    handler(err, { method: "POST", route: { path: "/users/:id" } } as any, {} as any, next);

    expect(client.calls).toHaveLength(1);
    expect(client.calls[0].extra).toEqual({
      http_method: "POST",
      http_path: "/users/:id",
    });
    expect(next).toHaveBeenCalledWith(err);
  });

  it("falls back to originalUrl when no route is matched", () => {
    const client = fakeClient();
    const handler = callistoErrorHandler(client);
    const next = vi.fn();

    handler(new Error("x"), { method: "GET", originalUrl: "/missing" } as any, {} as any, next);

    expect((client.calls[0].extra as any).http_path).toBe("/missing");
  });

  it("always calls next(err) even when shouldReport is false", () => {
    const client = fakeClient();
    const handler = callistoErrorHandler(client, { shouldReport: () => false });
    const err = new Error("x");
    const next = vi.fn();

    handler(err, { method: "GET", originalUrl: "/" } as any, {} as any, next);

    expect(client.calls).toHaveLength(0);
    expect(next).toHaveBeenCalledWith(err);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run test/integrations/express.test.ts`
Expected: FAIL — cannot resolve `../../src/integrations/express.js`.

- [ ] **Step 3: Write the implementation**

Create `callisto-sdk/js/src/integrations/express.ts`:

```ts
import type { ErrorRequestHandler, Request } from "express";
import {
  reportRequestError,
  type CallistoIntegrationOptions,
  type ErrorCapturer,
} from "./shared.js";

export type { CallistoIntegrationOptions, ErrorCapturer } from "./shared.js";

function requestPath(req: Request): string {
  return req.route?.path ?? req.originalUrl ?? req.url ?? "";
}

/**
 * Express error-handling middleware that reports unhandled errors to Callisto
 * and then re-throws by calling `next(err)`. Register it last, immediately
 * before your own error handler.
 *
 * @example
 *   app.use(callistoErrorHandler(client));
 *   app.use(myErrorHandler);
 */
export function callistoErrorHandler(
  client: ErrorCapturer,
  options?: CallistoIntegrationOptions,
): ErrorRequestHandler {
  return (err, req, _res, next) => {
    reportRequestError(
      client,
      err,
      { method: req.method, path: requestPath(req) },
      options,
    );
    next(err);
  };
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx vitest run test/integrations/express.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add js/src/integrations/express.ts js/test/integrations/express.test.ts
git commit -m "feat: add @callisto/sdk/express error handler"
```

---

### Task 4: NestJS interceptor (`nestjs.ts`)

**Files:**
- Create: `callisto-sdk/js/src/integrations/nestjs.ts`
- Test: `callisto-sdk/js/test/integrations/nestjs.test.ts`

- [ ] **Step 1: Write the failing test**

Create `callisto-sdk/js/test/integrations/nestjs.test.ts`:

```ts
import { describe, it, expect, vi } from "vitest";
import { throwError, of, lastValueFrom } from "rxjs";
import { CallistoInterceptor } from "../../src/integrations/nestjs.js";
import type { ErrorCapturer } from "../../src/integrations/shared.js";

function fakeClient(): ErrorCapturer & {
  calls: Array<{ err: unknown; level?: string; extra?: unknown }>;
} {
  const calls: Array<{ err: unknown; level?: string; extra?: unknown }> = [];
  return {
    calls,
    captureException(err, level, extra) {
      calls.push({ err, level, extra });
    },
  };
}

// Minimal ExecutionContext stub exposing an HTTP request.
function httpContext(req: unknown): any {
  return {
    switchToHttp: () => ({ getRequest: () => req }),
  };
}

describe("CallistoInterceptor", () => {
  it("reports the error with method/path and re-emits it untouched", async () => {
    const client = fakeClient();
    const interceptor = new CallistoInterceptor(client);
    const err = new Error("boom");
    const ctx = httpContext({ method: "POST", route: { path: "/x/:id" } });
    const callHandler = { handle: () => throwError(() => err) };

    const result$ = interceptor.intercept(ctx, callHandler as any);

    await expect(lastValueFrom(result$)).rejects.toBe(err);
    expect(client.calls).toHaveLength(1);
    expect(client.calls[0].extra).toEqual({
      http_method: "POST",
      http_path: "/x/:id",
    });
  });

  it("reports nothing on the success path", async () => {
    const client = fakeClient();
    const interceptor = new CallistoInterceptor(client);
    const ctx = httpContext({ method: "GET", originalUrl: "/ok" });
    const callHandler = { handle: () => of("ok") };

    const result = await lastValueFrom(interceptor.intercept(ctx, callHandler as any));

    expect(result).toBe("ok");
    expect(client.calls).toHaveLength(0);
  });

  it("respects shouldReport returning false but still propagates the error", async () => {
    const client = fakeClient();
    const interceptor = new CallistoInterceptor(client, { shouldReport: () => false });
    const err = new Error("x");
    const ctx = httpContext({ method: "GET", originalUrl: "/" });
    const callHandler = { handle: () => throwError(() => err) };

    await expect(lastValueFrom(interceptor.intercept(ctx, callHandler as any))).rejects.toBe(err);
    expect(client.calls).toHaveLength(0);
  });

  it("does not throw when the context has no HTTP request", async () => {
    const client = fakeClient();
    const interceptor = new CallistoInterceptor(client);
    const err = new Error("rpc");
    const ctx = { switchToHttp: () => ({ getRequest: () => undefined }) } as any;
    const callHandler = { handle: () => throwError(() => err) };

    await expect(lastValueFrom(interceptor.intercept(ctx, callHandler as any))).rejects.toBe(err);
    expect(client.calls).toHaveLength(1);
    expect((client.calls[0].extra as any).http_path).toBe("");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run test/integrations/nestjs.test.ts`
Expected: FAIL — cannot resolve `../../src/integrations/nestjs.js`.

- [ ] **Step 3: Write the implementation**

Create `callisto-sdk/js/src/integrations/nestjs.ts`:

```ts
import type {
  CallHandler,
  ExecutionContext,
  NestInterceptor,
} from "@nestjs/common";
import type { Observable } from "rxjs";
import { tap } from "rxjs";
import {
  reportRequestError,
  type CallistoIntegrationOptions,
  type ErrorCapturer,
} from "./shared.js";

export type { CallistoIntegrationOptions, ErrorCapturer } from "./shared.js";

interface HttpRequestLike {
  method?: string;
  route?: { path?: string };
  originalUrl?: string;
  url?: string;
}

function extractRequest(context: ExecutionContext): {
  method: string;
  path: string;
} {
  let req: HttpRequestLike | undefined;
  try {
    req = context.switchToHttp().getRequest<HttpRequestLike>();
  } catch {
    req = undefined;
  }
  return {
    method: req?.method ?? "",
    path: req?.route?.path ?? req?.originalUrl ?? req?.url ?? "",
  };
}

/**
 * NestJS interceptor that reports unhandled request errors to Callisto and
 * re-emits them untouched (no response shaping, no interference with your
 * exception filters).
 *
 * @example
 *   app.useGlobalInterceptors(new CallistoInterceptor(client));
 */
export class CallistoInterceptor implements NestInterceptor {
  constructor(
    private readonly client: ErrorCapturer,
    private readonly options?: CallistoIntegrationOptions,
  ) {}

  intercept(
    context: ExecutionContext,
    next: CallHandler,
  ): Observable<unknown> {
    const req = extractRequest(context);
    return next.handle().pipe(
      tap({
        error: (err: unknown) => {
          reportRequestError(this.client, err, req, this.options);
        },
      }),
    );
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx vitest run test/integrations/nestjs.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add js/src/integrations/nestjs.ts js/test/integrations/nestjs.test.ts
git commit -m "feat: add @callisto/sdk/nestjs interceptor"
```

---

### Task 5: Verify the build emits the subpath artifacts and full suite passes

**Files:**
- None (verification only)

- [ ] **Step 1: Build and confirm the integration artifacts exist**

Run (from `callisto-sdk/js/`):
```bash
npm run build
ls dist/integrations
```
Expected: `tsc` succeeds; `dist/integrations/` contains `shared.js`, `shared.d.ts`, `express.js`, `express.d.ts`, `nestjs.js`, `nestjs.d.ts`.

- [ ] **Step 2: Run the entire test suite**

Run: `npm test`
Expected: PASS — all pre-existing tests plus the 12 new integration tests, no failures.

- [ ] **Step 3: Confirm the subpaths resolve against the built output**

Run:
```bash
node --input-type=module -e "import('./dist/integrations/express.js').then(m => console.log(typeof m.callistoErrorHandler)); import('./dist/integrations/nestjs.js').then(m => console.log(typeof m.CallistoInterceptor));"
```
Expected: prints `function` and `function` (rxjs must be installed, which it is from Task 1).

- [ ] **Step 4: Commit (if anything changed; otherwise skip)**

```bash
git add -A
git commit -m "chore: verify integration build artifacts" || echo "nothing to commit"
```

---

### Task 6: Document the integrations in the README

**Files:**
- Modify: `callisto-sdk/js/README.md`

- [ ] **Step 1: Add a "Framework integrations" section**

Append the following section to `callisto-sdk/js/README.md` (after the existing
"Error reporting" section):

````markdown
## Framework integrations

The SDK ships optional Express and NestJS integrations that auto-report
unhandled request errors through the same [error reporter](#error-reporting).
They are **capture-only**: the error is always re-thrown / passed along, so your
own responses and error handling are never altered. They live behind subpath
imports, so the core package stays dependency-free.

Both accept `CallistoIntegrationOptions`:

| Option         | Type                                      | Default            | Description                                  |
| -------------- | ----------------------------------------- | ------------------ | -------------------------------------------- |
| `shouldReport` | `(err: unknown) => boolean`               | report everything  | Return `false` to skip reporting an error.   |
| `level`        | `string \| ((err: unknown) => string)`    | `"error"`          | Level for the reported event.                |

Only the request `method` and `path` are attached (as `http_method` /
`http_path`) — never the body, headers, or query.

### Express

```ts
import express from "express";
import { CallistoClient } from "@callisto/sdk";
import { callistoErrorHandler } from "@callisto/sdk/express";

const client = new CallistoClient({ errorDsn: process.env.CALLISTO_APP_ERROR_DSN });
const app = express();

// ...your routes...

// Register LAST, just before your own error handler:
app.use(callistoErrorHandler(client));
app.use((err, req, res, next) => {
  res.status(500).json({ error: "Internal Server Error" });
});
```

### NestJS

```ts
import { CallistoClient } from "@callisto/sdk";
import { CallistoInterceptor } from "@callisto/sdk/nestjs";

const client = new CallistoClient({ errorDsn: process.env.CALLISTO_APP_ERROR_DSN });

const app = await NestFactory.create(AppModule);
app.useGlobalInterceptors(new CallistoInterceptor(client));
await app.listen(3000);
```

Or register it as a global provider:

```ts
import { APP_INTERCEPTOR } from "@nestjs/core";
import { CallistoInterceptor } from "@callisto/sdk/nestjs";

@Module({
  providers: [
    {
      provide: APP_INTERCEPTOR,
      useFactory: (client: CallistoClient) => new CallistoInterceptor(client),
      inject: [CallistoClient],
    },
  ],
})
export class AppModule {}
```
````

- [ ] **Step 2: Commit**

```bash
git add js/README.md
git commit -m "docs: document express and nestjs integrations"
```

---

## Self-Review notes

- **Spec coverage:** packaging/exports (Task 1), `shared.ts` + `ErrorCapturer`/`CallistoIntegrationOptions`/`RequestInfo`/`reportRequestError` (Task 2), Express middleware with `next(err)` pass-through and route/originalUrl fallback (Task 3), NestJS interceptor with `tap({ error })`, non-HTTP guard, success path (Task 4), build/artifact + subpath resolution verification (Task 5), README (Task 6). All spec sections map to a task.
- **Type consistency:** `ErrorCapturer.captureException(error, level?, extra?)` matches `CallistoClient.captureException`'s signature; `reportRequestError(client, err, RequestInfo, options?)` signature is used identically in Tasks 3 and 4; `CallistoIntegrationOptions` (`shouldReport`, `level`) consistent across all files.
- **No placeholders:** every code step has full code; every run step has an expected result.
