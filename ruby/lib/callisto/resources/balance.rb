# frozen_string_literal: true

module Callisto
  # Account balance resource.
  class BalanceResource
    # @param transport [Transport]
    def initialize(transport)
      @t = transport
    end

    # Returns the account balance.
    #
    # @param format [String] response format (default "full")
    # @param currency [String, nil] filter by currency code
    # @return [Balance]
    def get(format: "full", currency: nil)
      data = @t.request("GET", "/sms/balance", query: { "format" => format, "currency" => currency })
      Balance.from_hash(data)
    end
  end
end
