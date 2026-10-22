package callisto

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

// DefaultLogsEndpoint is the callisto-logs OTLP/HTTP logs endpoint.
const DefaultLogsEndpoint = "https://logs.callistosignal.com/v1/logs"

// Delivery constants from the log-shipping contract.
const (
	logsMaxQueue            = 2000
	logsSendThreshold       = 100
	logsMaxBatchRecords     = 500
	logsMaxBatchBytes       = 1_000_000
	logsFlushInterval       = 5 * time.Second
	logsDefaultFlushTimeout = 5 * time.Second
	logsHTTPTimeout         = 10 * time.Second
	logsMaxAttempts         = 3
	logsAuthPause           = 60 * time.Second
	logsMaxRetryAfter       = 60 * time.Second
	logsDefaultRetryAfter   = 5 * time.Second
	logsDefaultServiceName  = "unknown_service"
)

// logsNetworkRetryDelays are the waits before the 2nd and 3rd attempt after a
// network error.
var logsNetworkRetryDelays = [...]time.Duration{time.Second, 5 * time.Second}

var (
	logsAccessKeyPattern = regexp.MustCompile(`^pk_[0-9a-f]{32}$`)
	logsSecretPattern    = regexp.MustCompile(`^sk_[0-9a-f]{48}$`)
)

// LogLevel is the severity of a shipped log record.
type LogLevel string

const (
	LogLevelDebug LogLevel = "debug"
	LogLevelInfo  LogLevel = "info"
	LogLevelWarn  LogLevel = "warn"
	LogLevelError LogLevel = "error"
	LogLevelFatal LogLevel = "fatal"
)

// normalizeLogLevel accepts any case and the "warning" / "critical" aliases;
// an unknown level is logged as info.
func normalizeLogLevel(level LogLevel) LogLevel {
	switch strings.ToLower(strings.TrimSpace(string(level))) {
	case "debug", "trace":
		return LogLevelDebug
	case "warn", "warning":
		return LogLevelWarn
	case "error":
		return LogLevelError
	case "fatal", "critical":
		return LogLevelFatal
	default:
		return LogLevelInfo
	}
}

// LogRequest is one encoded OTLP batch, ready to POST.
type LogRequest struct {
	// Endpoint is the full POST URL.
	Endpoint string
	// Header carries Authorization, Content-Type and User-Agent.
	Header http.Header
	// Body is the OTLP JSON ExportLogsServiceRequest.
	Body []byte
}

// LogResponse is what the logger needs from the ingest response.
type LogResponse struct {
	// StatusCode is the HTTP status.
	StatusCode int
	// RetryAfter is the raw Retry-After header ("" when absent).
	RetryAfter string
}

// LogSender POSTs one batch to callisto-logs. It returns a non-nil error only
// for a network failure (no HTTP response). Injectable via
// LoggerConfig.Sender, mainly for testing.
type LogSender interface {
	Send(ctx context.Context, req *LogRequest) (*LogResponse, error)
}

// httpLogSender is the default LogSender. It uses its own *http.Client, never
// the messaging transport.
type httpLogSender struct {
	client *http.Client
}

func (s *httpLogSender) Send(ctx context.Context, req *LogRequest) (*LogResponse, error) {
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, req.Endpoint, bytes.NewReader(req.Body))
	if err != nil {
		return nil, err
	}
	httpReq.Header = req.Header.Clone()
	resp, err := s.client.Do(httpReq)
	if err != nil {
		return nil, err
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
	_ = resp.Body.Close()
	return &LogResponse{StatusCode: resp.StatusCode, RetryAfter: resp.Header.Get("Retry-After")}, nil
}

// LoggerConfig configures a Logger built with NewLogger. Every field is
// optional; empty fields fall back to environment variables. With neither an
// access key nor a secret the logger is disabled (a cheap no-op).
type LoggerConfig struct {
	// AccessKey is the pk_… of a Logs-scope API key. Falls back to
	// CALLISTO_LOGS_ACCESS_KEY.
	AccessKey string
	// Secret is the sk_… of that key. Falls back to CALLISTO_LOGS_SECRET.
	Secret string
	// Endpoint is the full POST URL. Falls back to CALLISTO_LOGS_ENDPOINT, then
	// DefaultLogsEndpoint.
	Endpoint string
	// ServiceName is the resource service.name. Falls back to
	// CALLISTO_SERVICE_NAME, then OTEL_SERVICE_NAME, then "unknown_service".
	ServiceName string
	// Environment is the resource deployment.environment.name. Falls back to
	// CALLISTO_ENVIRONMENT; omitted when empty.
	Environment string
	// FlushTimeout bounds Flush and Close. Defaults to 5s.
	FlushTimeout time.Duration
	// HTTPClient is used by the default sender. Defaults to a client with a
	// 10s timeout. It is never shared with the messaging transport.
	HTTPClient *http.Client
	// Sender replaces the default HTTP sender (mainly for testing).
	Sender LogSender
	// Clock returns the time stamped on each record. Defaults to time.Now.
	Clock func() time.Time
}

// loggerSettings is the resolved LoggerConfig.
type loggerSettings struct {
	enabled      bool
	accessKey    string
	secret       string
	endpoint     string
	serviceName  string
	environment  string
	flushTimeout time.Duration
}

// resolveLoggerSettings applies env fallbacks and validates the credentials.
// It never puts a credential value in an error message.
func resolveLoggerSettings(cfg LoggerConfig) (loggerSettings, error) {
	s := loggerSettings{
		accessKey:    firstNonEmpty(cfg.AccessKey, os.Getenv("CALLISTO_LOGS_ACCESS_KEY")),
		secret:       firstNonEmpty(cfg.Secret, os.Getenv("CALLISTO_LOGS_SECRET")),
		endpoint:     firstNonEmpty(cfg.Endpoint, os.Getenv("CALLISTO_LOGS_ENDPOINT"), DefaultLogsEndpoint),
		serviceName:  firstNonEmpty(cfg.ServiceName, os.Getenv("CALLISTO_SERVICE_NAME"), os.Getenv("OTEL_SERVICE_NAME"), logsDefaultServiceName),
		environment:  firstNonEmpty(cfg.Environment, os.Getenv("CALLISTO_ENVIRONMENT")),
		flushTimeout: cfg.FlushTimeout,
	}
	if s.flushTimeout <= 0 {
		s.flushTimeout = logsDefaultFlushTimeout
	}
	if s.accessKey == "" && s.secret == "" {
		return s, nil // disabled
	}
	if s.accessKey == "" || s.secret == "" {
		return loggerSettings{}, errors.New(
			"callisto: logs AccessKey and Secret must be set together " +
				"(pass LoggerConfig fields or set CALLISTO_LOGS_ACCESS_KEY / CALLISTO_LOGS_SECRET)",
		)
	}
	if !logsAccessKeyPattern.MatchString(s.accessKey) {
		return loggerSettings{}, errors.New("callisto: logs AccessKey must be pk_ followed by 32 lowercase hex characters")
	}
	if !logsSecretPattern.MatchString(s.secret) {
		return loggerSettings{}, errors.New("callisto: logs Secret must be sk_ followed by 48 lowercase hex characters")
	}
	if !isSecureURL(s.endpoint) {
		return loggerSettings{}, errors.New(
			"callisto: logs Endpoint must be an absolute https URL " +
				"(http is accepted only for localhost, 127.0.0.1, ::1 and *.localhost)",
		)
	}
	s.enabled = true
	return s, nil
}

// loggerTuning holds delivery knobs that tests shorten.
type loggerTuning struct {
	interval time.Duration
	// wait replaces the retry wait; it returns false when the wait was
	// aborted. nil uses a real timer.
	wait func(ctx context.Context, d time.Duration) bool
}

// flushRequest asks the worker to send everything queued.
type flushRequest struct {
	ctx  context.Context
	done chan struct{}
	stop bool
}

// sendingKey marks the context of the logger's own requests, so the slog
// adapter can drop records emitted from inside a send.
type sendingKey struct{}

// Logger ships log records to callisto-logs over OTLP/HTTP JSON. Records are
// queued in memory and sent in batches by one background goroutine; a log call
// never blocks on the network and never panics. A Logger without credentials
// is a no-op. It is safe for concurrent use; the nil *Logger is a no-op too.
type Logger struct {
	settings loggerSettings
	sender   LogSender
	clock    func() time.Time
	tuning   loggerTuning
	envelope logEnvelope
	header   http.Header

	queue    chan *otlpLogRecord
	ctrl     chan flushRequest
	done     chan struct{}
	stopping chan struct{}
	ctx      context.Context
	cancel   context.CancelFunc

	closed      atomic.Bool
	dropped     atomic.Uint64
	pausedUntil atomic.Int64 // unix nanos; 0 = not paused
	sending     atomic.Bool
	workerGID   atomic.Uint64

	// draining is only touched by the worker goroutine.
	draining bool
}

// NewLogger builds a standalone Logger; it needs no messaging credentials. With
// no access key and no secret (arguments or env) it returns a disabled no-op
// logger. Half or malformed credentials, or an endpoint that is not https (http
// only on a loopback host), return a configuration error. (NewClient does not
// fail on them: it logs one warning and keeps a no-op logger.) Call Close
// before exit to send what is queued.
func NewLogger(cfg LoggerConfig) (*Logger, error) {
	return newLogger(cfg, loggerTuning{})
}

func newLogger(cfg LoggerConfig, tuning loggerTuning) (*Logger, error) {
	settings, err := resolveLoggerSettings(cfg)
	if err != nil {
		return nil, err
	}
	l := &Logger{settings: settings, sender: cfg.Sender, clock: cfg.Clock, tuning: tuning}
	if !settings.enabled {
		return l, nil
	}
	if l.clock == nil {
		l.clock = time.Now
	}
	if l.tuning.interval <= 0 {
		l.tuning.interval = logsFlushInterval
	}
	if l.sender == nil {
		client := cfg.HTTPClient
		if client == nil {
			client = &http.Client{Timeout: logsHTTPTimeout}
		}
		l.sender = &httpLogSender{client: client}
	}
	l.envelope = newLogEnvelope(settings.serviceName, settings.environment)
	auth := base64.StdEncoding.EncodeToString([]byte(settings.accessKey + ":" + settings.secret))
	l.header = http.Header{}
	l.header.Set("Authorization", "Basic "+auth)
	l.header.Set("Content-Type", "application/json")
	l.header.Set("User-Agent", "callisto-sdk-"+sdkLanguage+"/"+sdkVersion)

	l.queue = make(chan *otlpLogRecord, logsMaxQueue)
	l.ctrl = make(chan flushRequest)
	l.done = make(chan struct{})
	l.stopping = make(chan struct{})
	l.ctx, l.cancel = context.WithCancel(context.Background())
	go l.run()
	return l, nil
}

// Enabled reports whether the logger will actually send records.
func (l *Logger) Enabled() bool {
	return l != nil && l.settings.enabled && !l.closed.Load()
}

// DroppedCount is the number of records discarded without delivery: queue
// full, sending paused after a 401/403, or a batch dropped after a rejected or
// failed send. Read-only, for tests and diagnostics.
func (l *Logger) DroppedCount() uint64 {
	if l == nil {
		return 0
	}
	return l.dropped.Load()
}

// Debug logs at debug level. attrs may be nil.
func (l *Logger) Debug(msg string, attrs map[string]any) { l.Log(LogLevelDebug, msg, attrs) }

// Info logs at info level. attrs may be nil.
func (l *Logger) Info(msg string, attrs map[string]any) { l.Log(LogLevelInfo, msg, attrs) }

// Warn logs at warn level. attrs may be nil.
func (l *Logger) Warn(msg string, attrs map[string]any) { l.Log(LogLevelWarn, msg, attrs) }

// Error logs at error level. attrs may be nil.
func (l *Logger) Error(msg string, attrs map[string]any) { l.Log(LogLevelError, msg, attrs) }

// Fatal logs at fatal severity. Unlike log.Fatal it does not exit the process.
func (l *Logger) Fatal(msg string, attrs map[string]any) { l.Log(LogLevelFatal, msg, attrs) }

// Log queues one record at level (debug|info|warn|error|fatal; unknown levels
// are logged as info). Valid trace_id (32 hex) and span_id (16 hex) attributes
// become the record's traceId / spanId. It never blocks and never panics.
func (l *Logger) Log(level LogLevel, msg string, attrs map[string]any) {
	if !l.Enabled() {
		return
	}
	l.logAt(time.Time{}, normalizeLogLevel(level), msg, attrs)
}

// logAt builds and queues a record; a zero at means "now" from the clock.
func (l *Logger) logAt(at time.Time, level LogLevel, msg string, attrs map[string]any) {
	defer func() {
		if recover() != nil {
			l.dropped.Add(1)
		}
	}()
	if at.IsZero() {
		at = l.clock()
	}
	if l.isPaused(at) {
		l.dropped.Add(1)
		return
	}
	rec := buildLogRecord(at, level, msg, attrs)
	select {
	case l.queue <- rec:
	default:
		l.dropped.Add(1)
	}
}

func (l *Logger) isPaused(now time.Time) bool {
	until := l.pausedUntil.Load()
	return until != 0 && now.UnixNano() < until
}

// boundedContext applies FlushTimeout on top of ctx.
func (l *Logger) boundedContext(ctx context.Context) (context.Context, context.CancelFunc) {
	if ctx == nil {
		ctx = context.Background()
	}
	return context.WithTimeout(ctx, l.settings.flushTimeout)
}

// Flush sends everything queued now. It is bounded by ctx and FlushTimeout
// (default 5s) and returns the context error when the bound was hit. Delivery
// failures are not returned: they are swallowed and counted in DroppedCount.
func (l *Logger) Flush(ctx context.Context) error {
	if !l.Enabled() {
		return nil
	}
	ctx, cancel := l.boundedContext(ctx)
	defer cancel()
	req := flushRequest{ctx: ctx, done: make(chan struct{})}
	select {
	case l.ctrl <- req:
	case <-l.done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
	select {
	case <-req.done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// Close flushes what is queued, then stops the worker. It is bounded like
// Flush; later calls, and log calls after Close, are no-ops.
func (l *Logger) Close(ctx context.Context) error {
	if l == nil || !l.settings.enabled || !l.closed.CompareAndSwap(false, true) {
		return nil
	}
	close(l.stopping) // aborts a pending retry wait
	ctx, cancel := l.boundedContext(ctx)
	defer cancel()
	req := flushRequest{ctx: ctx, done: make(chan struct{}), stop: true}
	select {
	case l.ctrl <- req:
	case <-ctx.Done():
		l.cancel()
		return ctx.Err()
	}
	select {
	case <-l.done:
		l.cancel()
		return nil
	case <-ctx.Done():
		l.cancel()
		return ctx.Err()
	}
}

// run is the single worker goroutine.
func (l *Logger) run() {
	defer close(l.done)
	l.workerGID.Store(goroutineID())
	ticker := time.NewTicker(l.tuning.interval)
	defer ticker.Stop()

	var pending []*otlpLogRecord
	for {
		select {
		case rec := <-l.queue:
			pending = append(pending, rec)
			pending = l.drain(pending, logsMaxBatchRecords)
			if len(pending) >= logsSendThreshold {
				l.sendAll(l.ctx, pending)
				pending = nil
			}
		case <-ticker.C:
			pending = l.drain(pending, len(pending)+len(l.queue))
			if len(pending) > 0 {
				l.sendAll(l.ctx, pending)
				pending = nil
			}
		case req := <-l.ctrl:
			if req.stop {
				l.draining = true
			}
			pending = l.drain(pending, len(pending)+len(l.queue))
			l.sendAll(req.ctx, pending)
			pending = nil
			close(req.done)
			if req.stop {
				return
			}
		case <-l.ctx.Done():
			return
		}
	}
}

// drain moves queued records into pending without blocking, up to limit.
func (l *Logger) drain(pending []*otlpLogRecord, limit int) []*otlpLogRecord {
	for len(pending) < limit {
		select {
		case rec := <-l.queue:
			pending = append(pending, rec)
		default:
			return pending
		}
	}
	return pending
}

// sendAll encodes records and sends them in batches of at most 500 records and
// about 1 MB. Records left when ctx ends are dropped.
func (l *Logger) sendAll(ctx context.Context, records []*otlpLogRecord) {
	if len(records) == 0 {
		return
	}
	overhead := len(l.envelope.prefix) + len(l.envelope.suffix)
	var body bytes.Buffer
	count := 0
	flushBatch := func() {
		if count == 0 {
			return
		}
		body.Write(l.envelope.suffix)
		payload := append([]byte(nil), body.Bytes()...)
		n := count
		body.Reset()
		count = 0
		if ctx.Err() != nil {
			l.dropped.Add(uint64(n))
			return
		}
		l.deliver(ctx, payload, n)
	}
	for _, rec := range records {
		encoded, err := json.Marshal(rec)
		if err != nil {
			l.dropped.Add(1)
			continue
		}
		if count > 0 && (count >= logsMaxBatchRecords || body.Len()+1+len(encoded)+len(l.envelope.suffix) > logsMaxBatchBytes) {
			flushBatch()
		}
		if count == 0 {
			body.Grow(overhead + len(encoded))
			body.Write(l.envelope.prefix)
		} else {
			body.WriteByte(',')
		}
		body.Write(encoded)
		count++
	}
	flushBatch()
}

// deliver POSTs one batch with the contract's retry and pause rules. Every
// failure is swallowed; a dropped batch is counted.
func (l *Logger) deliver(ctx context.Context, body []byte, records int) {
	defer func() {
		if recover() != nil {
			l.dropped.Add(uint64(records))
		}
	}()
	drop := func() { l.dropped.Add(uint64(records)) }

	for attempt := 1; ; attempt++ {
		if l.isPaused(l.clock()) {
			drop()
			return
		}
		resp, err := l.send(ctx, body)

		var delay time.Duration
		switch {
		case err != nil || resp == nil:
			delay = logsNetworkRetryDelays[min(attempt-1, len(logsNetworkRetryDelays)-1)]
		case resp.StatusCode >= 200 && resp.StatusCode < 300:
			return
		case resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden:
			l.pausedUntil.Store(l.clock().Add(logsAuthPause).UnixNano())
			drop()
			return
		case resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode == http.StatusServiceUnavailable:
			delay = parseLogsRetryAfter(resp.RetryAfter)
		default: // 400, 413, 415 and anything else
			drop()
			return
		}

		if attempt >= logsMaxAttempts || !l.wait(ctx, delay) {
			drop()
			return
		}
	}
}

// send performs one request with the per-request HTTP timeout. The request
// context is marked so the slog adapter can recognise the logger's own path.
func (l *Logger) send(ctx context.Context, body []byte) (*LogResponse, error) {
	reqCtx, cancel := context.WithTimeout(context.WithValue(ctx, sendingKey{}, l), logsHTTPTimeout)
	defer cancel()
	l.sending.Store(true)
	defer l.sending.Store(false)
	return l.sender.Send(reqCtx, &LogRequest{
		Endpoint: l.settings.endpoint,
		Header:   l.header.Clone(),
		Body:     body,
	})
}

// wait sleeps d before a retry. It returns false when ctx ends, or when Close
// starts while the worker is still in normal operation.
func (l *Logger) wait(ctx context.Context, d time.Duration) bool {
	if l.tuning.wait != nil {
		return l.tuning.wait(ctx, d)
	}
	var stop <-chan struct{}
	if !l.draining {
		stop = l.stopping
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-ctx.Done():
		return false
	case <-stop:
		return false
	}
}

// parseLogsRetryAfter reads Retry-After seconds, capped at 60s; absent or
// unparsable → 5s.
func parseLogsRetryAfter(v string) time.Duration {
	n, err := strconv.Atoi(strings.TrimSpace(v))
	if err != nil || n < 0 {
		return logsDefaultRetryAfter
	}
	d := time.Duration(n) * time.Second
	if d > logsMaxRetryAfter {
		return logsMaxRetryAfter
	}
	return d
}

// inSendPath reports whether a record is being emitted from inside the
// logger's own send (recursion guard for the slog adapter).
func (l *Logger) inSendPath(ctx context.Context) bool {
	if ctx != nil && ctx.Value(sendingKey{}) != nil {
		return true
	}
	return l.sending.Load() && goroutineID() == l.workerGID.Load()
}

// goroutineID returns the current goroutine's id, parsed from its stack
// header ("goroutine 42 [running]:"). Only used on the rare send path.
func goroutineID() uint64 {
	var buf [64]byte
	n := runtime.Stack(buf[:], false)
	s := strings.TrimPrefix(string(buf[:n]), "goroutine ")
	if i := strings.IndexByte(s, ' '); i > 0 {
		id, _ := strconv.ParseUint(s[:i], 10, 64)
		return id
	}
	return 0
}
