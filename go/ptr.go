package callisto

// Int returns a pointer to v, for setting optional *int parameters (Page,
// PerPage, Limit, ExpiredIn, DigitSize) inline:
//
//	client.SMS.List(ctx, callisto.ListSMSParams{Page: callisto.Int(2)})
func Int(v int) *int { return &v }

// Ptr returns a pointer to v of any type, for setting optional pointer
// parameters inline.
func Ptr[T any](v T) *T { return &v }
