package callisto

import (
	"context"
	"log/slog"
	"reflect"
	"time"
)

// Handler returns a log/slog handler that forwards records to this logger:
//
//	slog.SetDefault(slog.New(logger.Handler()))
//
// Levels map to severity (below Info → debug, Info → info, Warn → warn, Error
// and above → error). Attributes become record attributes, groups become nested
// maps, and WithAttrs / WithGroup are supported. The first top-level attribute
// holding an error also sets exception.type and exception.message. Records
// emitted from inside the logger's own send are dropped, never re-shipped. On a
// disabled logger the handler is disabled too.
func (l *Logger) Handler() slog.Handler {
	return &slogHandler{logger: l}
}

// slogScope is one WithAttrs call, recorded with the group path open at the
// time it was made.
type slogScope struct {
	groups []string
	attrs  []slog.Attr
}

type slogHandler struct {
	logger *Logger
	scopes []slogScope
	groups []string
}

func (h *slogHandler) Enabled(context.Context, slog.Level) bool {
	return h.logger.Enabled()
}

func (h *slogHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	if len(attrs) == 0 {
		return h
	}
	clone := *h
	clone.scopes = append(append([]slogScope(nil), h.scopes...), slogScope{
		groups: h.groups,
		attrs:  append([]slog.Attr(nil), attrs...),
	})
	return &clone
}

func (h *slogHandler) WithGroup(name string) slog.Handler {
	if name == "" {
		return h
	}
	clone := *h
	clone.groups = append(append([]string(nil), h.groups...), name)
	return &clone
}

func (h *slogHandler) Handle(ctx context.Context, r slog.Record) error {
	l := h.logger
	if !l.Enabled() || l.inSendPath(ctx) {
		return nil
	}
	defer func() { _ = recover() }()

	root := map[string]any{}
	for _, scope := range h.scopes {
		for _, a := range scope.attrs {
			addSlogAttr(root, scope.groups, a)
		}
	}
	r.Attrs(func(a slog.Attr) bool {
		addSlogAttr(root, h.groups, a)
		return true
	})
	addExceptionFields(root)

	at := r.Time
	if at.IsZero() {
		at = l.clock()
	}
	l.logAt(at, slogLevel(r.Level), r.Message, root)
	return nil
}

// slogLevel maps a slog level to a log level.
func slogLevel(level slog.Level) LogLevel {
	switch {
	case level < slog.LevelInfo:
		return LogLevelDebug
	case level < slog.LevelWarn:
		return LogLevelInfo
	case level < slog.LevelError:
		return LogLevelWarn
	default:
		return LogLevelError
	}
}

// addSlogAttr places a under the group path in root. Group maps are created
// only when something is put in them, so empty groups are omitted.
func addSlogAttr(root map[string]any, groups []string, a slog.Attr) {
	a.Value = a.Value.Resolve()
	if a.Equal(slog.Attr{}) {
		return
	}
	if a.Value.Kind() == slog.KindGroup {
		members := a.Value.Group()
		if len(members) == 0 {
			return
		}
		path := groups
		if a.Key != "" { // an empty key inlines the group
			path = append(append([]string(nil), groups...), a.Key)
		}
		for _, m := range members {
			addSlogAttr(root, path, m)
		}
		return
	}
	target := root
	for _, g := range groups {
		next, ok := target[g].(map[string]any)
		if !ok {
			next = map[string]any{}
			target[g] = next
		}
		target = next
	}
	target[a.Key] = slogValue(a.Value)
}

// slogValue converts a resolved, non-group slog value to a plain Go value.
func slogValue(v slog.Value) any {
	switch v.Kind() {
	case slog.KindString:
		return v.String()
	case slog.KindInt64:
		return v.Int64()
	case slog.KindUint64:
		return v.Uint64()
	case slog.KindFloat64:
		return v.Float64()
	case slog.KindBool:
		return v.Bool()
	case slog.KindDuration:
		return v.Duration().String()
	case slog.KindTime:
		return v.Time().Format(time.RFC3339Nano)
	default:
		return v.Any()
	}
}

// addExceptionFields sets exception.type / exception.message from the first
// (by key) top-level attribute holding a non-nil error.
func addExceptionFields(root map[string]any) {
	if _, ok := root["exception.message"]; ok {
		return
	}
	var key string
	var found error
	for k, v := range root {
		if err, ok := v.(error); ok && err != nil && !isNilPointer(err) && (found == nil || k < key) {
			key, found = k, err
		}
	}
	if found == nil {
		return
	}
	root["exception.type"] = errorTypeLabel(found)
	root["exception.message"] = found.Error()
}

// errorTypeLabel names an error's concrete type, e.g. "*fs.PathError".
func errorTypeLabel(err error) string {
	return reflect.TypeOf(err).String()
}
