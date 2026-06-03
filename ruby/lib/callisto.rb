# frozen_string_literal: true

require_relative "callisto/version"
require_relative "callisto/errors"
require_relative "callisto/enums"
require_relative "callisto/models"
require_relative "callisto/config"
require_relative "callisto/error_reporter"
require_relative "callisto/transport"
require_relative "callisto/resources/balance"
require_relative "callisto/resources/sms"
require_relative "callisto/resources/otp"
require_relative "callisto/resources/whatsapp"
require_relative "callisto/resources/notify"
require_relative "callisto/client"

# Top-level namespace for the Callisto messaging API SDK.
module Callisto
  DEFAULT_BASE_URL = Config::DEFAULT_BASE_URL
end
