package callisto

import "fmt"

// Client is the Callisto API client. Construct it with NewClient and access the
// resources via the exported service fields. It is safe for concurrent use.
type Client struct {
	transport *transport
	reporter  *ErrorReporter

	// Balance accesses the balance resource.
	Balance *BalanceService
	// SMS accesses the SMS resource.
	SMS *SMSService
	// OTP accesses the OTP resource.
	OTP *OTPService
	// WhatsApp accesses the WhatsApp resource.
	WhatsApp *WhatsAppService
	// Notify accesses the notify resource.
	Notify *NotifyService
}

// NewClient builds a Client from the given Options, applying environment-variable
// fallbacks and defaults. It returns an error (never panics) when the required
// credentials cannot be resolved.
func NewClient(opts Options) (*Client, error) {
	cfg, err := resolveConfig(opts)
	if err != nil {
		return nil, err
	}

	reporter := newErrorReporter(cfg.errorDSN, cfg.environment, opts.ErrorSender)
	tr := newTransport(cfg, opts.HTTPClient, reporter)

	c := &Client{transport: tr, reporter: reporter}
	c.Balance = &BalanceService{t: tr}
	c.SMS = &SMSService{t: tr}
	c.OTP = &OTPService{t: tr}
	c.WhatsApp = &WhatsAppService{t: tr}
	c.Notify = &NotifyService{t: tr}
	return c, nil
}

// CaptureException reports an error to the configured error DSN. It is a no-op
// when error reporting is disabled. Delivery is background and best-effort.
func (c *Client) CaptureException(err error, opts ...CaptureOption) {
	c.reporter.CaptureException(err, opts...)
}

// CaptureMessage reports a plain message to the configured error DSN at the
// given level (fatal|error|warning|info). No-op when reporting is disabled.
func (c *Client) CaptureMessage(message, level string) {
	c.reporter.CaptureMessage(message, level, nil)
}

// SetUser sets (or clears, with nil) the user context attached to reported
// events.
func (c *Client) SetUser(user map[string]any) {
	c.reporter.SetUser(user)
}

// Recover is a deferred panic handler that reports an in-flight panic at level
// "fatal" and then re-panics, preserving normal crash semantics. Use it as:
//
//	defer client.Recover()
//
// Go has no process-wide uncaught-exception hook, so this explicit helper is the
// idiomatic equivalent of the other SDKs' opt-in global handler.
func (c *Client) Recover() {
	if rec := recover(); rec != nil {
		err, ok := rec.(error)
		if !ok {
			err = fmt.Errorf("%v", rec)
		}
		c.reporter.capture(err, "fatal", nil, "", "")
		c.reporter.Flush()
		panic(rec)
	}
}

// Close flushes any pending reported events and stops the reporter's background
// worker. It is safe to call multiple times.
func (c *Client) Close() {
	c.reporter.Close()
}
