package callisto

import (
	"context"
	"encoding/json"
)

// BalanceService accesses the balance resource.
type BalanceService struct {
	t *transport
}

// BalanceParams are the parameters for Balance.Get.
type BalanceParams struct {
	// Format is the response format. Defaults to "full" when empty.
	Format string
	// Currency optionally filters the balance by currency code.
	Currency string
}

// Get returns the account balance. GET /sms/balance.
func (s *BalanceService) Get(ctx context.Context, params BalanceParams) (*Balance, error) {
	format := params.Format
	if format == "" {
		format = "full"
	}
	query := map[string]any{"format": format}
	if params.Currency != "" {
		query["currency"] = params.Currency
	}
	raw, err := s.t.request(ctx, "GET", "/sms/balance", nil, query)
	if err != nil {
		return nil, err
	}
	var out Balance
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}
