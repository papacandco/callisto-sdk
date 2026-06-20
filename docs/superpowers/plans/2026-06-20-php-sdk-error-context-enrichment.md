# PHP SDK Error-Context Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Additively enrich the PHP SDK's captured-error payload with request detail (url, query, headers, ip) and richer context (runtime, server, trace, memory/timing), behind a hard PII redaction guarantee.

**Architecture:** Framework bridges collect *raw* request data and pass it to `CallistoIntegration::request()`, which packages it; `ErrorReporter` performs all redaction centrally and builds the `request.*` and `context.*` blocks. New data nests under the already-persisted `request`/`context` keys (the ingest stores those JSON columns verbatim), so no server change is needed.

**Tech Stack:** PHP 8.4, PHPUnit (`composer test` / `phpunit`), repo `callisto-sdk/php`.

## Global Constraints

- **PII (hard):** never transmit clientId/apiKey/Authorization/cookies/the outgoing request body. Sensitive header/query values are replaced with the literal `[Filtered]`.
- Redaction lives **only** in `ErrorReporter` (single tested place) — it must hold for every call path (3 bridges, Transport, direct callers). Bridges pass raw data.
- **No new top-level payload keys** — the ingest drops unknown ones. New request detail nests under `request.*`; all new context nests under `context.*`.
- Backward compatible: `culprit` still derives from `method`/`path`; existing `{method, path}`-only callers (Transport) must produce an unchanged `request` block (new keys appear only when their source data is present).
- Capture is best-effort and never throws (keep the existing try/catch swallow).
- Redaction placeholder string is exactly `[Filtered]`. Header keys Title-Cased; multi-value headers joined with `, `. Caps: ≤ 50 headers, ≤ 50 query keys, value ≤ 1024 bytes.
- Test runner: `./vendor/bin/phpunit` (single: `./vendor/bin/phpunit --filter testName`). Recording sender: `Callisto\Sdk\Tests\Support\RecordingSender` (`->payloads`, `->last()`).
- Do not reformat the whole tree; lint only changed files.

## File Structure

- Modify `src/ErrorReporter.php` — central redaction helpers; request-block enrichment; context enrichment.
- Modify `src/Integration/CallistoIntegration.php` — widen `request()` to package url/query/headers/ip.
- Modify `src/Framework/Laravel/CallistoServiceProvider.php` — extract fullUrl/query/headers/ip.
- Modify `src/Framework/Symfony/CallistoExceptionListener.php` — extract uri/query/headers/ip.
- Modify `src/Framework/Bowphp/CallistoErrorHandler.php` — extract url/headers/ip (+query if available), guarded.
- Tests: `tests/ReporterTest.php`, `tests/Integration/CallistoIntegrationTest.php`, `tests/Framework/BowErrorHandlerTest.php`, `tests/Framework/SymfonyExceptionListenerTest.php`.

---

### Task 1: Central redaction + request-block enrichment in `ErrorReporter`

**Files:**
- Modify: `src/ErrorReporter.php`
- Test: `tests/ReporterTest.php`

**Interfaces:**
- Consumes: `$request` array may now carry `url?:string, query?:array, headers?:array, ip?:string` alongside `method,path`.
- Produces: payload `request` block `{method, path, url?, query_string?, headers?, ip?}` with sensitive values redacted to `[Filtered]`. Private helpers: `redactMap(array,bool):array`, `isSensitiveKey(string):bool`, `titleCaseHeader(string):string`, `stripQuery(string):string`, `resolveIp(array):?string`.

- [ ] **Step 1: Write failing tests** (append to `tests/ReporterTest.php`)

```php
    public function testRequestBlockEnrichedAndRedacted(): void
    {
        $this->reporter()->captureException(
            new ValidationException('boom'),
            'error',
            null,
            [
                'method' => 'POST',
                'path' => '/orders',
                'url' => 'http://host/orders?token=abc&page=2',
                'query' => ['token' => 'abc', 'page' => '2'],
                'headers' => [
                    'accept' => '*/*',
                    'authorization' => 'Bearer secret-xyz',
                    'x-custom' => ['a', 'b'],
                ],
                'ip' => '203.0.113.7',
            ],
        );

        $req = $this->sender->lastPayload()['request'];
        $this->assertSame('POST', $req['method']);
        $this->assertSame('/orders', $req['path']);
        // url keeps scheme+host+path, drops the query string.
        $this->assertSame('http://host/orders', $req['url']);
        // query: sensitive key redacted, normal key kept.
        $this->assertSame('[Filtered]', $req['query_string']['token']);
        $this->assertSame('2', $req['query_string']['page']);
        // headers: Title-Cased keys, sensitive redacted, multi-value joined.
        $this->assertSame('*/*', $req['headers']['Accept']);
        $this->assertSame('[Filtered]', $req['headers']['Authorization']);
        $this->assertSame('a, b', $req['headers']['X-Custom']);
        $this->assertSame('203.0.113.7', $req['ip']);

        // Hard guarantee: the raw secret never appears anywhere in the payload.
        $encoded = json_encode($this->sender->lastPayload());
        $this->assertStringNotContainsString('secret-xyz', (string) $encoded);
    }

    public function testIpResolvedFromProxyHeadersWhenNotExplicit(): void
    {
        $this->reporter()->captureException(
            new ValidationException('x'),
            'error',
            null,
            [
                'method' => 'GET',
                'path' => '/x',
                'headers' => ['X-Forwarded-For' => '198.51.100.9, 10.0.0.1'],
            ],
        );
        $this->assertSame('198.51.100.9', $this->sender->lastPayload()['request']['ip']);
    }

    public function testMethodPathOnlyRequestIsUnchanged(): void
    {
        $this->reporter()->captureException(
            new ValidationException('x'),
            'error',
            null,
            ['method' => 'GET', 'path' => '/x'],
        );
        $this->assertSame(['method' => 'GET', 'path' => '/x'], $this->sender->lastPayload()['request']);
    }
```

- [ ] **Step 2: Run, verify they fail**

Run: `./vendor/bin/phpunit --filter 'testRequestBlockEnrichedAndRedacted|testIpResolvedFromProxyHeadersWhenNotExplicit|testMethodPathOnlyRequestIsUnchanged'`
Expected: FAIL (e.g. missing `url`/`query_string`/redaction; current code emits only method/path).

- [ ] **Step 3: Implement in `src/ErrorReporter.php`**

Add constants near the existing ones:

```php
    /** Replacement for any redacted header/query value. */
    private const REDACTED = '[Filtered]';

    /** Header/query keys whose value must never be transmitted. */
    private const SENSITIVE_KEYS = [
        'authorization', 'cookie', 'set-cookie', 'proxy-authorization',
        'x-api-key', 'x-csrf-token', 'x-xsrf-token',
    ];

    /** Key-name patterns that also force redaction. */
    private const SENSITIVE_KEY_PATTERN = '/authoriz|cookie|secret|token|password|passwd|api[-_]?key|csrf|session/i';

    /** Caps to keep the request block bounded. */
    private const MAX_MAP_ITEMS = 50;
    private const MAX_VALUE_BYTES = 1024;
```

Replace the existing `$request !== null` block in `buildExceptionPayload()` with:

```php
        if ($request !== null) {
            $req = [
                'method' => $request['method'],
                'path' => $request['path'],
            ];
            if (isset($request['url']) && is_string($request['url'])) {
                $req['url'] = $this->stripQuery($request['url']);
            }
            if (isset($request['query']) && is_array($request['query']) && $request['query'] !== []) {
                $req['query_string'] = $this->redactMap($request['query'], false);
            }
            if (isset($request['headers']) && is_array($request['headers']) && $request['headers'] !== []) {
                $req['headers'] = $this->redactMap($request['headers'], true);
            }
            $ip = $this->resolveIp($request);
            if ($ip !== null) {
                $req['ip'] = $ip;
            }
            $payload['request'] = $req;
        }
```

Add the helpers:

```php
    /**
     * Redact a header/query map: sensitive keys' values become [Filtered];
     * other values are flattened (arrays joined) and length-capped. The map size
     * is capped. When $titleCaseKeys, header names are normalized to Title-Case.
     *
     * @param array<array-key, mixed> $map
     * @return array<string, string>
     */
    private function redactMap(array $map, bool $titleCaseKeys): array
    {
        $out = [];
        $count = 0;
        foreach ($map as $key => $value) {
            if ($count >= self::MAX_MAP_ITEMS) {
                break;
            }
            $count++;
            $rawKey = (string) $key;
            $name = $titleCaseKeys ? $this->titleCaseHeader($rawKey) : $rawKey;
            if ($this->isSensitiveKey($rawKey)) {
                $out[$name] = self::REDACTED;
                continue;
            }
            $flat = is_array($value)
                ? implode(', ', array_map(static fn ($v): string => (string) $v, $value))
                : (string) $value;
            if (strlen($flat) > self::MAX_VALUE_BYTES) {
                $flat = substr($flat, 0, self::MAX_VALUE_BYTES);
            }
            $out[$name] = $flat;
        }

        return $out;
    }

    private function isSensitiveKey(string $key): bool
    {
        $lower = strtolower($key);
        if (in_array($lower, self::SENSITIVE_KEYS, true)) {
            return true;
        }

        return preg_match(self::SENSITIVE_KEY_PATTERN, $lower) === 1;
    }

    private function titleCaseHeader(string $name): string
    {
        $parts = explode('-', strtolower($name));

        return implode('-', array_map(static fn (string $p): string => ucfirst($p), $parts));
    }

    private function stripQuery(string $url): string
    {
        $pos = strpos($url, '?');

        return $pos === false ? $url : substr($url, 0, $pos);
    }

    /**
     * Resolve the client IP: an explicit `ip` wins; otherwise derive from proxy
     * headers in order Cf-Connecting-Ip → X-Forwarded-For (first) → X-Real-Ip.
     *
     * @param array<string, mixed> $request
     */
    private function resolveIp(array $request): ?string
    {
        if (isset($request['ip']) && is_string($request['ip']) && $request['ip'] !== '') {
            return $request['ip'];
        }
        $headers = $request['headers'] ?? null;
        if (!is_array($headers)) {
            return null;
        }
        $lower = [];
        foreach ($headers as $k => $v) {
            $lower[strtolower((string) $k)] = is_array($v) ? (string) ($v[0] ?? '') : (string) $v;
        }
        foreach (['cf-connecting-ip', 'x-forwarded-for', 'x-real-ip'] as $h) {
            if (!empty($lower[$h])) {
                $val = $lower[$h];
                if ($h === 'x-forwarded-for') {
                    $val = trim(explode(',', $val)[0]);
                }

                return $val;
            }
        }

        return null;
    }
```

Also extend the docblocks of `captureException()` and `buildExceptionPayload()` `@param` for `$request` to `array{method:string,path:string,url?:string,query?:array,headers?:array,ip?:string}|null`, and add a one-line comment that the request body (`data`) is deliberately never captured.

- [ ] **Step 4: Run, verify pass**

Run: `./vendor/bin/phpunit --filter 'testRequestBlockEnrichedAndRedacted|testIpResolvedFromProxyHeadersWhenNotExplicit|testMethodPathOnlyRequestIsUnchanged'`
Expected: PASS.

- [ ] **Step 5: Run the full ReporterTest to confirm no regressions**

Run: `./vendor/bin/phpunit tests/ReporterTest.php`
Expected: PASS (existing leak-guard + all prior tests still green).

- [ ] **Step 6: Commit**

```bash
git add src/ErrorReporter.php tests/ReporterTest.php
git commit -m "feat(reporter): enrich + redact request block (url/query/headers/ip)"
```

---

### Task 2: Context enrichment in `ErrorReporter` (runtime / server / trace / stats)

**Files:**
- Modify: `src/ErrorReporter.php`
- Test: `tests/ReporterTest.php`

**Interfaces:**
- Produces: `context.runtime` `{name,version,sapi,os,os_version}`, `context.server.name`, `context.runtime_stats.{memory_peak_bytes,duration_ms?}`, `context.trace.{trace_id, request_id?}`. `trace_id` = 32 hex. `request_id` from inbound `X-Request-Id` header when present.
- Consumes (Task 1): the `$request` headers map (for `request_id`).

- [ ] **Step 1: Write failing tests** (append to `tests/ReporterTest.php`)

```php
    public function testContextCarriesRuntimeServerTraceAndStats(): void
    {
        $this->reporter()->captureMessage('hi', 'info');
        $ctx = $this->sender->lastPayload()['context'];

        $this->assertSame('php', $ctx['runtime']['name']);
        $this->assertSame(PHP_VERSION, $ctx['runtime']['version']);
        $this->assertArrayHasKey('sapi', $ctx['runtime']);
        $this->assertArrayHasKey('os', $ctx['runtime']);
        $this->assertArrayHasKey('name', $ctx['server']);
        $this->assertArrayHasKey('memory_peak_bytes', $ctx['runtime_stats']);
        $this->assertMatchesRegularExpression('/^[0-9a-f]{32}$/', $ctx['trace']['trace_id']);
    }

    public function testRequestIdLiftedFromHeaderIntoContextTrace(): void
    {
        $this->reporter()->captureException(
            new ValidationException('x'),
            'error',
            null,
            ['method' => 'GET', 'path' => '/x', 'headers' => ['X-Request-Id' => 'req-123']],
        );
        $this->assertSame('req-123', $this->sender->lastPayload()['context']['trace']['request_id']);
    }
```

- [ ] **Step 2: Run, verify they fail**

Run: `./vendor/bin/phpunit --filter 'testContextCarriesRuntimeServerTraceAndStats|testRequestIdLiftedFromHeaderIntoContextTrace'`
Expected: FAIL (no runtime/server/trace keys yet).

- [ ] **Step 3: Implement in `src/ErrorReporter.php`**

In `buildContext()`, before `return $context;`, add the framework-independent blocks:

```php
        $context['runtime'] = [
            'name' => 'php',
            'version' => PHP_VERSION,
            'sapi' => php_sapi_name(),
            'os' => php_uname('s'),
            'os_version' => php_uname('r'),
        ];
        $host = gethostname();
        if ($host !== false) {
            $context['server'] = ['name' => $host];
        }
        $stats = ['memory_peak_bytes' => memory_get_peak_usage(true)];
        if (isset($_SERVER['REQUEST_TIME_FLOAT'])) {
            $stats['duration_ms'] = round((microtime(true) - (float) $_SERVER['REQUEST_TIME_FLOAT']) * 1000, 2);
        }
        $context['runtime_stats'] = $stats;
        $context['trace'] = ['trace_id' => bin2hex(random_bytes(16))];
```

In `buildExceptionPayload()`, after `$context = $this->buildContext($extra);` add the request-id lift (uses the helper below):

```php
        $requestId = $this->requestIdFrom($request);
        if ($requestId !== null) {
            $context['trace']['request_id'] = $requestId;
        }
```

Add the helper:

```php
    /**
     * The inbound X-Request-Id (case-insensitive) for log correlation, or null.
     *
     * @param array<string, mixed>|null $request
     */
    private function requestIdFrom(?array $request): ?string
    {
        $headers = $request['headers'] ?? null;
        if (!is_array($headers)) {
            return null;
        }
        foreach ($headers as $k => $v) {
            if (strtolower((string) $k) === 'x-request-id') {
                $val = is_array($v) ? (string) ($v[0] ?? '') : (string) $v;

                return $val === '' ? null : $val;
            }
        }

        return null;
    }
```

- [ ] **Step 4: Run, verify pass**

Run: `./vendor/bin/phpunit --filter 'testContextCarriesRuntimeServerTraceAndStats|testRequestIdLiftedFromHeaderIntoContextTrace'`
Expected: PASS.

- [ ] **Step 5: Full ReporterTest + integration regression**

Run: `./vendor/bin/phpunit tests/ReporterTest.php tests/Integration/CallistoIntegrationTest.php`
Expected: PASS (context additions don't break existing key-specific assertions).

- [ ] **Step 6: Commit**

```bash
git add src/ErrorReporter.php tests/ReporterTest.php
git commit -m "feat(reporter): add runtime/server/trace/stats to context"
```

---

### Task 3: Widen `CallistoIntegration::request()`

**Files:**
- Modify: `src/Integration/CallistoIntegration.php`
- Test: `tests/Integration/CallistoIntegrationTest.php`

**Interfaces:**
- Produces: `CallistoIntegration::request(?string $method, ?string $path, ?string $url = null, ?array $query = null, ?array $headers = null, ?string $ip = null): ?array` — packages present optional keys raw (`url`, `query`, `headers`, `ip`); returns null only when method/path missing. No redaction here.

- [ ] **Step 1: Write failing tests** (append to `tests/Integration/CallistoIntegrationTest.php`)

```php
    public function testRequestPackagesOptionalFieldsRaw(): void
    {
        $this->assertSame(
            [
                'method' => 'POST',
                'path' => '/p',
                'url' => 'http://h/p?x=1',
                'query' => ['x' => '1'],
                'headers' => ['Authorization' => 'Bearer t'],
                'ip' => '1.2.3.4',
            ],
            CallistoIntegration::request('post', '/p', 'http://h/p?x=1', ['x' => '1'], ['Authorization' => 'Bearer t'], '1.2.3.4'),
        );
    }

    public function testRequestOmitsEmptyOptionalFields(): void
    {
        $this->assertSame(
            ['method' => 'GET', 'path' => '/p'],
            CallistoIntegration::request('GET', '/p', null, [], [], null),
        );
    }
```

- [ ] **Step 2: Run, verify they fail**

Run: `./vendor/bin/phpunit --filter 'testRequestPackagesOptionalFieldsRaw|testRequestOmitsEmptyOptionalFields'`
Expected: FAIL (too many args to current `request()`).

- [ ] **Step 3: Implement** — replace `request()` in `src/Integration/CallistoIntegration.php`:

```php
    /**
     * Shape an inbound request into the reporter's contract, or null when method
     * or path is missing. Optional url/query/headers/ip are packaged RAW —
     * redaction is the reporter's responsibility.
     *
     * @param array<string, mixed>|null $query
     * @param array<string, mixed>|null $headers
     * @return array{method:string,path:string,url?:string,query?:array<string,mixed>,headers?:array<string,mixed>,ip?:string}|null
     */
    public static function request(
        ?string $method,
        ?string $path,
        ?string $url = null,
        ?array $query = null,
        ?array $headers = null,
        ?string $ip = null,
    ): ?array {
        $method = $method !== null ? trim($method) : '';
        $path = $path !== null ? trim($path) : '';
        if ($method === '' || $path === '') {
            return null;
        }

        $req = ['method' => strtoupper($method), 'path' => $path];
        if ($url !== null && $url !== '') {
            $req['url'] = $url;
        }
        if ($query !== null && $query !== []) {
            $req['query'] = $query;
        }
        if ($headers !== null && $headers !== []) {
            $req['headers'] = $headers;
        }
        if ($ip !== null && $ip !== '') {
            $req['ip'] = $ip;
        }

        return $req;
    }
```

- [ ] **Step 4: Run, verify pass**

Run: `./vendor/bin/phpunit --filter 'testRequestPackagesOptionalFieldsRaw|testRequestOmitsEmptyOptionalFields|testRequestShaping'`
Expected: PASS (incl. the existing `testRequestShaping`, unchanged).

- [ ] **Step 5: Commit**

```bash
git add src/Integration/CallistoIntegration.php tests/Integration/CallistoIntegrationTest.php
git commit -m "feat(integration): widen request() to carry url/query/headers/ip"
```

---

### Task 4: Framework bridges extract the new request detail

**Files:**
- Modify: `src/Framework/Bowphp/CallistoErrorHandler.php`
- Modify: `src/Framework/Symfony/CallistoExceptionListener.php`
- Modify: `src/Framework/Laravel/CallistoServiceProvider.php`
- Test: `tests/Framework/BowErrorHandlerTest.php`, `tests/Framework/SymfonyExceptionListenerTest.php`

**Interfaces:**
- Consumes: `CallistoIntegration::request(method, path, url, query, headers, ip)` (Task 3).
- Produces: each bridge's request shaper now forwards url/query/headers/ip when the framework request exposes them (all accessors `method_exists`-guarded so a missing one degrades to null, never fatal).

- [ ] **Step 1: Write failing tests**

Extend the Bow stand-in and add a test in `tests/Framework/BowErrorHandlerTest.php`. Replace the private `request()` helper with one that also exposes headers/query/ip/url, and add:

```php
    /** A richer stand-in for Bow\Http\Request (duck-typed). */
    private function richRequest(): object
    {
        return new class {
            public function method(): string
            {
                return 'post';
            }

            public function path(): string
            {
                return '/orders';
            }

            public function url(): string
            {
                return 'http://host/orders?token=abc';
            }

            /** @return array<string,mixed> */
            public function query(): array
            {
                return ['token' => 'abc', 'page' => '2'];
            }

            /** @return array<string,mixed> */
            public function getHeaders(): array
            {
                return ['Authorization' => 'Bearer secret', 'Accept' => '*/*'];
            }

            public function ip(): string
            {
                return '203.0.113.7';
            }
        };
    }

    public function testRequestFromForwardsRichFieldsRaw(): void
    {
        $req = CallistoErrorHandler::requestFrom($this->richRequest());
        $this->assertSame('POST', $req['method']);
        $this->assertSame('/orders', $req['path']);
        $this->assertSame('http://host/orders?token=abc', $req['url']);   // raw; reporter strips/redacts
        $this->assertSame(['token' => 'abc', 'page' => '2'], $req['query']);
        $this->assertSame(['Authorization' => 'Bearer secret', 'Accept' => '*/*'], $req['headers']);
        $this->assertSame('203.0.113.7', $req['ip']);
    }
```

In `tests/Framework/SymfonyExceptionListenerTest.php`, add a test using a stand-in event/request exposing `getUri()`, `query->all()`, `headers->all()`, `getClientIp()` (mirror the existing stand-in style in that file) asserting the listener forwards url/query/headers/ip into the captured payload's `request` (assert `$sender->last()['request']['url']` etc., and that `Authorization` is `[Filtered]` after the reporter redacts).

- [ ] **Step 2: Run, verify they fail**

Run: `./vendor/bin/phpunit tests/Framework/BowErrorHandlerTest.php tests/Framework/SymfonyExceptionListenerTest.php`
Expected: FAIL (bridges only forward method/path today).

- [ ] **Step 3: Implement — Bowphp** (`src/Framework/Bowphp/CallistoErrorHandler.php`)

Replace the body of `requestFrom()`:

```php
    public static function requestFrom(?object $request): ?array
    {
        if ($request === null) {
            return null;
        }

        $method = method_exists($request, 'method') ? $request->method() : null;
        $path = method_exists($request, 'path') ? $request->path() : null;
        $url = method_exists($request, 'url') ? $request->url() : null;
        $query = method_exists($request, 'query') ? $request->query() : null;
        $headers = method_exists($request, 'getHeaders') ? $request->getHeaders() : null;
        $ip = method_exists($request, 'ip') ? $request->ip() : null;

        return CallistoIntegration::request(
            is_string($method) ? $method : null,
            is_string($path) ? $path : null,
            is_string($url) ? $url : null,
            is_array($query) ? $query : null,
            is_array($headers) ? $headers : null,
            is_string($ip) ? $ip : null,
        );
    }
```

- [ ] **Step 4: Implement — Symfony** (`src/Framework/Symfony/CallistoExceptionListener.php`)

In `requestContext()`, after deriving `$method`/`$path`, derive the rest guarded and pass them through:

```php
        $url = method_exists($request, 'getUri') ? (string) $request->getUri() : null;

        $query = null;
        if (isset($request->query) && is_object($request->query) && method_exists($request->query, 'all')) {
            $all = $request->query->all();
            $query = is_array($all) ? $all : null;
        }

        $headers = null;
        if (isset($request->headers) && is_object($request->headers) && method_exists($request->headers, 'all')) {
            $all = $request->headers->all();
            $headers = is_array($all) ? $all : null;
        }

        $ip = method_exists($request, 'getClientIp') ? $request->getClientIp() : null;

        return CallistoIntegration::request(
            $method,
            $path,
            $url,
            $query,
            $headers,
            is_string($ip) ? $ip : null,
        );
```

- [ ] **Step 5: Implement — Laravel** (`src/Framework/Laravel/CallistoServiceProvider.php`)

In `requestContext()`, after `$method`/`$path`, derive guarded and pass through:

```php
        $url = method_exists($request, 'fullUrl') ? (string) $request->fullUrl() : null;
        $query = method_exists($request, 'query') ? $request->query() : null;
        $headers = (isset($request->headers) && is_object($request->headers) && method_exists($request->headers, 'all'))
            ? $request->headers->all()
            : null;
        $ip = method_exists($request, 'ip') ? $request->ip() : null;

        return CallistoIntegration::request(
            $method,
            $path,
            $url,
            is_array($query) ? $query : null,
            is_array($headers) ? $headers : null,
            is_string($ip) ? $ip : null,
        );
```

- [ ] **Step 6: Run, verify pass**

Run: `./vendor/bin/phpunit tests/Framework/BowErrorHandlerTest.php tests/Framework/SymfonyExceptionListenerTest.php`
Expected: PASS.

- [ ] **Step 7: Full suite**

Run: `./vendor/bin/phpunit`
Expected: PASS (no regressions across reporter/integration/framework suites).

- [ ] **Step 8: Commit**

```bash
git add src/Framework/Bowphp/CallistoErrorHandler.php src/Framework/Symfony/CallistoExceptionListener.php src/Framework/Laravel/CallistoServiceProvider.php tests/Framework/BowErrorHandlerTest.php tests/Framework/SymfonyExceptionListenerTest.php
git commit -m "feat(framework): bridges forward url/query/headers/ip to reporter"
```

---

## Self-Review

**Spec coverage:**
- Header denylist + `[Filtered]` redaction, central in reporter → Task 1. ✓
- Request fields url/query/ip/full-url → Task 1 (build/redact) + Task 3/4 (collect). ✓
- More context: runtime/server/trace/memory-timing → Task 2. ✓
- request_id from X-Request-Id → Task 2. ✓
- Widened `request()` raw packaging → Task 3. ✓
- All 3 bridges extract → Task 4. ✓
- Additive/nested under request.*/context.*, no new top-level keys → Tasks 1–2 (no top-level additions). ✓
- Backward compat (method/path only unchanged; culprit unchanged) → Task 1 `testMethodPathOnlyRequestIsUnchanged`. ✓
- No body ever; redaction covers all paths → Task 1 leak assertion + central placement. ✓

**Placeholder scan:** none; every step has full code/commands.

**Type consistency:** `request()` optional-arg order `(method, path, url, query, headers, ip)` is identical in Task 3 definition and all Task 4 call sites. Reporter consumes `request['url'|'query'|'headers'|'ip']` (raw) and emits `request['url'|'query_string'|'headers'|'ip']` consistently across Tasks 1–2. `redactMap(array,bool)` signature stable.

## Notes for the executor

- Work in an isolated worktree of `callisto-sdk/php` (the repo is on `main`; branch first). The parent repo `callisto-sdk` holds the spec/plan docs.
- Symfony/Laravel request objects are duck-typed (no framework dependency in the SDK). Verify the real accessor names if a framework test is available; the stand-in tests define the contract. For Bow, `query()`/`getHeaders()`/`url()`/`ip()` are assumed and guarded — if Bow's real API differs, adjust the guarded accessor names (the guards make a mismatch a graceful null, not a fatal).
- Do not capture request body anywhere (no `->all()`/`->request->all()` that would include body) — query only.
