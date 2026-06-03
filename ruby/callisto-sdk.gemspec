# frozen_string_literal: true

require_relative "lib/callisto/version"

Gem::Specification.new do |spec|
  spec.name = "callisto-sdk"
  spec.version = Callisto::VERSION
  spec.authors = ["Callisto"]
  spec.summary = "Official Callisto messaging API SDK for Ruby"
  spec.description = "Ruby client for the Callisto messaging API (SMS, OTP, WhatsApp, Notify) " \
                     "built on the standard library Net::HTTP and JSON — no runtime dependencies."
  spec.homepage = "https://callistosignal.com"
  spec.license = "MIT"
  spec.required_ruby_version = ">= 3.0"

  spec.metadata["homepage_uri"] = spec.homepage

  spec.files = Dir.glob("lib/**/*.rb") + ["README.md"]
  spec.require_paths = ["lib"]

  spec.add_development_dependency "rspec", "~> 3.12"
  spec.add_development_dependency "webmock", "~> 3.19"
end
