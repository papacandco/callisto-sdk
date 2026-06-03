package callisto

import (
	"errors"
	"net/http"
	"os"
	"strings"
	"time"
)

// DefaultBaseURL is the default Callisto API base URL.
const DefaultBaseURL = "https://api.callistosignal.com/v1"

// defaultTimeout is the default request timeout when none is supplied.
const defaultTimeout = 30 * time.Second

// Options configures a Client. ClientID and APIKey are required (directly or via
// the CALLISTO_CLIENT_ID / CALLISTO_API_KEY environment variables). All other
// fields are optional.
type Options struct {
	// ClientID is the Callisto client ID. Falls back to CALLISTO_CLIENT_ID.
	ClientID string
	// APIKey is the Callisto API key. Falls back to CALLISTO_API_KEY.
	APIKey string
	// BaseURL overrides the API base URL. Falls back to CALLISTO_BASE_URL, then
	// DefaultBaseURL. Any trailing slash is trimmed.
	BaseURL string
	// Timeout is the per-request timeout. Defaults to 30s when zero.
	Timeout time.Duration
	// HTTPClient is an optional pre-configured *http.Client to inject (advanced
	// use, e.g. custom transport, proxies, or testing). When provided, its
	// Timeout is used as-is.
	HTTPClient *http.Client
	// ErrorDSN is the error-reporting ingest DSN. Falls back to
	// CALLISTO_ERROR_DSN. Absent → error reporting is fully disabled.
	ErrorDSN string
	// Environment is an optional tag attached to reported errors. Falls back to
	// CALLISTO_ENVIRONMENT.
	Environment string
	// ErrorSender is an optional custom sender for reported errors (mainly for
	// testing). When nil, the default HTTP sender is used.
	ErrorSender ErrorSender
}

// config holds the resolved, validated configuration.
type config struct {
	clientID    string
	apiKey      string
	baseURL     string
	timeout     time.Duration
	errorDSN    string
	environment string
}

// resolveConfig applies environment-variable fallbacks and defaults to the
// supplied Options, validating that the credentials are present. It returns a
// fail-fast error (never panics) when ClientID or APIKey is unresolved.
func resolveConfig(opts Options) (config, error) {
	clientID := firstNonEmpty(opts.ClientID, os.Getenv("CALLISTO_CLIENT_ID"))
	apiKey := firstNonEmpty(opts.APIKey, os.Getenv("CALLISTO_API_KEY"))
	if clientID == "" || apiKey == "" {
		return config{}, errors.New(
			"callisto: ClientID and APIKey are required " +
				"(pass Options fields or set CALLISTO_CLIENT_ID / CALLISTO_API_KEY)",
		)
	}

	baseURL := firstNonEmpty(opts.BaseURL, os.Getenv("CALLISTO_BASE_URL"), DefaultBaseURL)
	baseURL = strings.TrimRight(baseURL, "/")

	timeout := opts.Timeout
	if timeout <= 0 {
		timeout = defaultTimeout
	}

	errorDSN := firstNonEmpty(opts.ErrorDSN, os.Getenv("CALLISTO_ERROR_DSN"))
	environment := firstNonEmpty(opts.Environment, os.Getenv("CALLISTO_ENVIRONMENT"))

	return config{
		clientID:    clientID,
		apiKey:      apiKey,
		baseURL:     baseURL,
		timeout:     timeout,
		errorDSN:    errorDSN,
		environment: environment,
	}, nil
}

// firstNonEmpty returns the first non-empty string among its arguments.
func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}
