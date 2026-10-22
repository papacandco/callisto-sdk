# frozen_string_literal: true

require "net/http"
require "uri"
require "json"

module Callisto
  # Ships application log lines to callisto-logs (OTLP/HTTP JSON) in the background.
  #
  # Construction never needs messaging credentials. Every argument is optional and falls back
  # to an environment variable. With neither a Logs access key nor a secret, the logger is
  # disabled: every call is a cheap no-op and no thread is started.
  #
  # Delivery: records go into a bounded in-memory queue (2 000; extra records are dropped). One
  # background thread sends a batch when 100 records are queued, every 5 s otherwise, and on
  # #flush / #close. 429/503 and network errors are retried (3 attempts in total); 401/403 pause
  # sending for 60 s. Every record lost without delivery is counted in #dropped_count. A log
  # call never raises and never waits on the network. After a fork, the child starts with an
  # empty queue and its own worker thread.
  #
  # Isolation: the logger has its own Net::HTTP path. It never uses the messaging Transport,
  # never sends client_id / api_key, and its own failures are swallowed (never reported, never
  # logged through itself).
  #
  # Note: inside the gem this class shadows Ruby's standard library logger; refer to that one
  # as +::Logger+.
  #
  # @example
  #   logger = Callisto::Logger.new(logs_access_key: "pk_...", logs_secret: "sk_...",
  #                                 service_name: "checkout")
  #   logger.info("order paid", { order_id: 42, trace_id: span.trace_id })
  #   logger.close
  class Logger
    DEFAULT_ENDPOINT = "https://logs.callistosignal.com/v1/logs"
    DEFAULT_SERVICE_NAME = "unknown_service"

    ACCESS_KEY_PATTERN = /\Apk_[0-9a-f]{32}\z/.freeze
    SECRET_PATTERN = /\Ask_[0-9a-f]{48}\z/.freeze
    TRACE_ID_PATTERN = /\A[0-9a-fA-F]{32}\z/.freeze
    SPAN_ID_PATTERN = /\A[0-9a-fA-F]{16}\z/.freeze

    # level => [severityNumber, severityText]
    SEVERITIES = {
      "debug" => [5, "DEBUG"],
      "info" => [9, "INFO"],
      "warn" => [13, "WARN"],
      "error" => [17, "ERROR"],
      "fatal" => [21, "FATAL"]
    }.freeze
    LEVEL_ALIASES = { "warning" => "warn" }.freeze

    MAX_QUEUE = 2_000
    BATCH_TRIGGER = 100
    MAX_BATCH_RECORDS = 500
    MAX_BATCH_BYTES = 1_000_000
    FLUSH_INTERVAL = 5.0
    DEFAULT_FLUSH_TIMEOUT = 5.0
    HTTP_TIMEOUT = 10.0

    MAX_BODY_CHARS = 32_768
    MAX_ATTRIBUTE_CHARS = 8_192
    MAX_ATTRIBUTES = 128
    # Items kept per nested Array / Hash; the extra ones are dropped.
    MAX_NESTED_ITEMS = 128
    # Encoded values (scalars + containers) per record, across all attributes and nesting levels.
    # A sub-array or sub-hash referenced several times counts each time it is visited.
    MAX_RECORD_VALUES = 2_048
    # http:// is accepted only for these hosts (and *.localhost): local development.
    LOOPBACK_HOSTS = %w[localhost 127.0.0.1 ::1].freeze
    MAX_DEPTH = 5
    ELLIPSIS = "…"

    MAX_ATTEMPTS = 3
    NETWORK_RETRY_DELAYS = [1.0, 5.0].freeze
    DEFAULT_RETRY_AFTER = 5.0
    MAX_RETRY_AFTER = 60.0
    AUTH_PAUSE_SECONDS = 60

    NANOS_PER_SECOND = 1_000_000_000
    INT64_RANGE = (-(2**63)..(2**63 - 1)).freeze

    # Default sender: one Net::HTTP POST. Returns [status, { "retry-after" => value }], or nil
    # on a transport failure. Never raises.
    class HttpSender
      def initialize(timeout = HTTP_TIMEOUT)
        @timeout = timeout
      end

      # @param url [String] the logs endpoint
      # @param headers [Hash{String=>String}] request headers
      # @param body [String] the serialized OTLP JSON body
      # @return [Array(Integer, Hash), nil]
      def call(url, headers, body)
        uri = URI.parse(url)
        req = Net::HTTP::Post.new(uri)
        headers.each { |name, value| req[name] = value }
        req.body = body

        http = Net::HTTP.new(uri.host, uri.port)
        http.use_ssl = uri.scheme == "https"
        http.open_timeout = @timeout
        http.read_timeout = @timeout
        http.write_timeout = @timeout if http.respond_to?(:write_timeout=)

        res = http.request(req)
        [res.code.to_i, { "retry-after" => res["Retry-After"] }]
      rescue StandardError
        nil
      end
    end

    # One flush() caller waiting for the worker to drain.
    class Waiter
      def initialize
        @mutex = Mutex.new
        @cv = ConditionVariable.new
        @done = false
      end

      def done!
        @mutex.synchronize do
          @done = true
          @cv.broadcast
        end
      end

      # @return [Boolean] whether the worker signalled before the timeout
      def wait(timeout)
        deadline = Logger.monotonic + timeout
        @mutex.synchronize do
          until @done
            remaining = deadline - Logger.monotonic
            break if remaining <= 0

            @cv.wait(@mutex, remaining)
          end
          @done
        end
      end
    end

    @registry = {}
    @registry_mutex = Mutex.new
    @at_exit_installed = false

    class << self
      # @api private
      def monotonic
        Process.clock_gettime(Process::CLOCK_MONOTONIC)
      end

      # @api private Keeps a live logger so the single at_exit hook can close it.
      def register(logger)
        @registry_mutex.synchronize do
          @registry[logger.object_id] = logger
          unless @at_exit_installed
            @at_exit_installed = true
            at_exit { Logger.close_all }
          end
        end
      end

      # @api private
      def unregister(logger)
        @registry_mutex.synchronize { @registry.delete(logger.object_id) }
      end

      # @api private Closes every logger still open (at process exit).
      def close_all
        loggers = @registry_mutex.synchronize { @registry.values }
        loggers.each(&:close)
      rescue StandardError
        nil
      end
    end

    attr_reader :endpoint, :service_name, :environment, :flush_timeout

    # Whether +url+ is an absolute https URL, or an http URL on a loopback host (localhost,
    # 127.0.0.1, ::1, *.localhost). Secrets (the Logs sk_, the error DSN) never travel in clear
    # text.
    # @param url [String, nil]
    # @return [Boolean]
    def self.secure_url?(url)
      uri = URI.parse(url.to_s.strip)
      return false unless uri.is_a?(URI::HTTP) && uri.absolute?

      host = uri.host.to_s.downcase.delete_prefix("[").delete_suffix("]")
      return false if host.empty?
      return true if uri.scheme.casecmp?("https")

      LOOPBACK_HOSTS.include?(host) || host.end_with?(".localhost")
    rescue StandardError
      false
    end

    # A disabled logger that reads no credential setting: every call is a no-op. The client
    # uses it when the Logs settings are invalid.
    # @return [Logger]
    def self.disabled(service_name: nil, environment: nil)
      logger = allocate
      logger.send(:init_disabled, service_name, environment)
      logger
    end

    # @param logs_access_key [String, nil] pk_... of a Logs key; falls back to
    #   CALLISTO_LOGS_ACCESS_KEY
    # @param logs_secret [String, nil] sk_... of that key; falls back to CALLISTO_LOGS_SECRET
    # @param logs_endpoint [String, nil] full POST URL; falls back to CALLISTO_LOGS_ENDPOINT,
    #   then https://logs.callistosignal.com/v1/logs
    # @param service_name [String, nil] resource service.name; falls back to
    #   CALLISTO_SERVICE_NAME, then OTEL_SERVICE_NAME, then "unknown_service"
    # @param environment [String, nil] resource deployment.environment.name; falls back to
    #   CALLISTO_ENVIRONMENT
    # @param flush_timeout [Float] bound, in seconds, of #flush and #close
    # @param sender [#call, nil] injectable sender +call(url, headers, body)+ returning
    #   +[status, headers]+ (or an Integer status), nil or raising on a network error
    # @param clock [#call, nil] returns the current time as Integer nanoseconds since the epoch;
    #   used for record timestamps and the 401/403 pause
    # @param sleeper [#call, nil] +call(seconds)+ used for retry delays (tests inject a recorder)
    # @param flush_interval [Float] seconds between timed sends
    # @raise [ValidationError] when only one credential is present, a credential is malformed,
    #   or the endpoint is not an absolute https URL (http only for localhost, 127.0.0.1, ::1 and
    #   *.localhost). Callisto::Client does not raise on these: it warns once and keeps a
    #   disabled logger (see Logger.disabled).
    def initialize(logs_access_key: nil, logs_secret: nil, logs_endpoint: nil,
                   service_name: nil, environment: nil, flush_timeout: DEFAULT_FLUSH_TIMEOUT,
                   sender: nil, clock: nil, sleeper: nil, flush_interval: FLUSH_INTERVAL)
      access_key = presence(logs_access_key) || presence(ENV["CALLISTO_LOGS_ACCESS_KEY"])
      secret = presence(logs_secret) || presence(ENV["CALLISTO_LOGS_SECRET"])
      @endpoint = presence(logs_endpoint) || presence(ENV["CALLISTO_LOGS_ENDPOINT"]) ||
                  DEFAULT_ENDPOINT
      @service_name = presence(service_name) || presence(ENV["CALLISTO_SERVICE_NAME"]) ||
                      presence(ENV["OTEL_SERVICE_NAME"]) || DEFAULT_SERVICE_NAME
      @environment = presence(environment) || presence(ENV["CALLISTO_ENVIRONMENT"])
      @flush_timeout = flush_timeout.to_f
      @flush_interval = flush_interval.to_f
      @dropped = 0
      @closed = false
      @thread = nil
      @mutex = nil

      validate!(access_key, secret)

      @enabled = !access_key.nil?
      return unless @enabled

      @headers = {
        "Authorization" => "Basic " + ["#{access_key}:#{secret}"].pack("m0"),
        "Content-Type" => "application/json",
        "User-Agent" => "callisto-sdk-ruby/#{Callisto::VERSION}"
      }.freeze
      @sender = sender || HttpSender.new(HTTP_TIMEOUT)
      @clock = clock || -> { Process.clock_gettime(Process::CLOCK_REALTIME, :nanosecond) }
      @sleeper = sleeper
      @prefix, @suffix = build_envelope
      @fork_mutex = Mutex.new

      reset_state
      start_worker
      Logger.register(self)
    end

    # @return [Boolean] whether a Logs key resolved (false → every call is a no-op)
    def enabled?
      @enabled
    end

    # @return [Integer] records lost without delivery in this process (read-only diagnostics):
    #   queue full, dropped during a 401/403 pause, rejected by a non-retryable status, given up
    #   after 3 attempts, or still unsent when #close stopped the worker
    def dropped_count
      return @dropped if @mutex.nil?

      check_fork
      @mutex.synchronize { @dropped }
    end

    def debug(message, attributes = {})
      log("debug", message, attributes)
    end

    def info(message, attributes = {})
      log("info", message, attributes)
    end

    def warn(message, attributes = {})
      log("warn", message, attributes)
    end

    def error(message, attributes = {})
      log("error", message, attributes)
    end

    def fatal(message, attributes = {})
      log("fatal", message, attributes)
    end

    # Queues one record. Never raises, never waits on the network.
    #
    # @param level [String, Symbol] debug|info|warn|error|fatal (unknown → info)
    # @param message [Object] converted with #to_s
    # @param attributes [Hash] flat or nested; trace_id / span_id tie the record to a trace
    # @return [nil]
    def log(level, message, attributes = {})
      return nil if !@enabled || @closed

      json = JSON.generate(build_record(level, message, attributes))
      check_fork
      @mutex.synchronize do
        if paused? || @buffer.size >= MAX_QUEUE
          @dropped += 1
          return nil
        end

        @buffer << json
        @cv.signal if @buffer.size >= BATCH_TRIGGER
      end
      ensure_worker
      nil
    rescue StandardError
      nil
    end

    # Sends what is queued now. Bounded by +timeout+ (default flush_timeout); never raises.
    # @return [nil]
    def flush(timeout = nil)
      return nil if !@enabled || @closed

      check_fork
      return nil unless ensure_worker

      request_flush(timeout.nil? ? @flush_timeout : timeout.to_f)
      nil
    rescue StandardError
      nil
    end

    # Flushes, then stops the worker. Bounded by flush_timeout in total; later calls (and later
    # log calls) are no-ops. Never raises.
    # @return [nil]
    def close
      return nil if !@enabled || @closed

      @closed = true
      check_fork
      unless ensure_worker
        Logger.unregister(self)
        return nil
      end

      deadline = Logger.monotonic + @flush_timeout
      request_flush(@flush_timeout)
      @mutex.synchronize do
        @stopping = true
        @cv.broadcast
        @stop_cv.broadcast
      end
      remaining = deadline - Logger.monotonic
      @thread&.join(remaining.positive? ? remaining : 0)
      Logger.unregister(self)
      nil
    rescue StandardError
      nil
    end

    private

    def init_disabled(service_name, environment)
      @endpoint = DEFAULT_ENDPOINT
      @service_name = presence(service_name) || DEFAULT_SERVICE_NAME
      @environment = presence(environment)
      @flush_timeout = DEFAULT_FLUSH_TIMEOUT.to_f
      @flush_interval = FLUSH_INTERVAL.to_f
      @dropped = 0
      @closed = false
      @thread = nil
      @mutex = nil
      @enabled = false
    end

    def presence(value)
      return nil if value.nil?

      str = value.to_s
      str.strip.empty? ? nil : str
    end

    def validate!(access_key, secret)
      if access_key.nil? ^ secret.nil?
        raise ValidationError.new(
          "Callisto: logs_access_key and logs_secret must be set together " \
          "(pass both or set CALLISTO_LOGS_ACCESS_KEY / CALLISTO_LOGS_SECRET)."
        )
      end
      return if access_key.nil?

      unless access_key.match?(ACCESS_KEY_PATTERN)
        raise ValidationError.new(
          "Callisto: logs_access_key must be a Logs access key (pk_ followed by 32 lowercase hex)."
        )
      end
      unless secret.match?(SECRET_PATTERN)
        raise ValidationError.new(
          "Callisto: logs_secret must be a Logs secret (sk_ followed by 48 lowercase hex)."
        )
      end

      return if Logger.secure_url?(@endpoint)

      raise ValidationError.new(
        "Callisto: logs_endpoint must be an absolute https URL " \
        "(http is accepted only for localhost, 127.0.0.1, ::1 and *.localhost)."
      )
    end

    # Pre-serializes the resource and scope wrapping every batch.
    def build_envelope
      resource = [string_kv("service.name", @service_name)]
      resource << string_kv("deployment.environment.name", @environment) unless @environment.nil?
      resource << string_kv("telemetry.sdk.name", "callisto-sdk")
      resource << string_kv("telemetry.sdk.language", "ruby")
      resource << string_kv("telemetry.sdk.version", Callisto::VERSION)
      scope = { name: "callisto-sdk", version: Callisto::VERSION }

      prefix = '{"resourceLogs":[{"resource":{"attributes":' + JSON.generate(resource) +
               '},"scopeLogs":[{"scope":' + JSON.generate(scope) + ',"logRecords":['
      [prefix, "]}]}]}"]
    end

    def string_kv(key, value)
      { key: key, value: { stringValue: value } }
    end

    # ---- record mapping (caller thread) ----

    def build_record(level, message, attributes)
      number, text = SEVERITIES.fetch(normalize_level(level))
      now = @clock.call.to_i.to_s
      attrs = attributes.is_a?(Hash) ? attributes.dup : {}
      trace_id = take_id(attrs, "trace_id", TRACE_ID_PATTERN)
      span_id = take_id(attrs, "span_id", SPAN_ID_PATTERN)

      record = {
        timeUnixNano: now,
        observedTimeUnixNano: now,
        severityNumber: number,
        severityText: text,
        body: { stringValue: truncate(clean_string(message.to_s), MAX_BODY_CHARS) }
      }
      kvs = key_values(attrs, 1, MAX_ATTRIBUTES, ValueBudget.new)
      record[:attributes] = kvs unless kvs.empty?
      record[:traceId] = trace_id unless trace_id.nil?
      record[:spanId] = span_id unless span_id.nil?
      record
    end

    def normalize_level(level)
      name = level.to_s.downcase
      name = LEVEL_ALIASES.fetch(name, name)
      SEVERITIES.key?(name) ? name : "info"
    end

    # Moves a valid trace_id / span_id (string or symbol key) out of the attributes.
    def take_id(attrs, name, pattern)
      key = [name, name.to_sym].find { |k| attrs.key?(k) }
      return nil if key.nil?

      value = attrs[key]
      return nil unless value.is_a?(String) && value.match?(pattern) && value.match?(/[^0]/)

      attrs.delete(key)
      value.downcase
    end

    # The encoded values left for one record.
    class ValueBudget
      def initialize(left = MAX_RECORD_VALUES)
        @left = left
      end

      def take
        return false if @left <= 0

        @left -= 1
        true
      end

      def spent?
        @left <= 0
      end
    end

    def key_values(hash, depth, limit = nil, budget = ValueBudget.new)
      out = []
      hash.each do |key, value|
        break if limit && out.size >= limit
        next if value.nil?

        mapped = any_value(value, depth, budget)
        break if mapped.nil? # budget spent: this and every later value are dropped

        out << { key: clean_string(key.to_s), value: mapped }
      end
      out
    end

    # Maps a Ruby value to an OTLP AnyValue; nil once the record's value budget is spent.
    def any_value(value, depth, budget = ValueBudget.new)
      return nil unless budget.take

      case value
      when String, Symbol
        { stringValue: truncate(clean_string(value.to_s), MAX_ATTRIBUTE_CHARS) }
      when true, false
        { boolValue: value }
      when Integer
        INT64_RANGE.cover?(value) ? { intValue: value.to_s } : { stringValue: value.to_s }
      when Float
        float_value(value)
      when Array
        return stringified(value, budget) if depth > MAX_DEPTH

        { arrayValue: { values: array_values(value, depth, budget) } }
      when Hash
        return stringified(value, budget) if depth > MAX_DEPTH

        { kvlistValue: { values: key_values(value, depth + 1, MAX_NESTED_ITEMS, budget) } }
      else
        stringified(value, budget)
      end
    end

    def array_values(array, depth, budget)
      values = []
      array.each do |item|
        break if values.size >= MAX_NESTED_ITEMS

        if item.nil?
          break unless budget.take

          values << {}
          next
        end
        mapped = any_value(item, depth + 1, budget)
        break if mapped.nil?

        values << mapped
      end
      values
    end

    def float_value(value)
      return { stringValue: "NaN" } if value.nan?
      return { stringValue: value.positive? ? "Infinity" : "-Infinity" } if value.infinite?

      { doubleValue: value }
    end

    def stringified(value, budget = ValueBudget.new)
      text = begin
        value.is_a?(Array) || value.is_a?(Hash) ? bounded_json(value, budget) : value.to_s
      rescue StandardError
        "#<#{value.class}>"
      end
      { stringValue: truncate(clean_string(text), MAX_ATTRIBUTE_CHARS) }
    end

    # JSON of a container nested too deep, written piece by piece instead of JSON.generate:
    # every nested item takes one value from the record budget and the output stops past
    # MAX_ATTRIBUTE_CHARS, so a shared or cyclic structure costs a bounded amount of work.
    # A cut ends with "…".
    def bounded_json(value, budget)
      out = +""
      active = {}.compare_by_identity
      cut = false
      write = lambda do |text|
        throw :bounded_json_stop if out.length > MAX_ATTRIBUTE_CHARS

        out << text
      end
      walk = lambda do |item, count|
        if count && !budget.take
          cut = true
          throw :bounded_json_stop
        end
        unless item.is_a?(Array) || item.is_a?(Hash)
          write.call(scalar_json(item))
          next
        end
        if active.key?(item) || active.size >= 64
          write.call(item.is_a?(Hash) ? "{}" : "[]")
          next
        end
        active[item] = true
        if item.is_a?(Hash)
          write.call("{")
          item.each_with_index do |(k, v), i|
            write.call(",") if i.positive?
            write.call(clean_string(k.to_s).to_json + ":")
            walk.call(v, true)
          end
          write.call("}")
        else
          write.call("[")
          item.each_with_index do |v, i|
            write.call(",") if i.positive?
            walk.call(v, true)
          end
          write.call("]")
        end
        active.delete(item)
      end
      catch(:bounded_json_stop) { walk.call(value, false) }
      cut ? out + "…" : out
    end

    def scalar_json(item)
      case item
      when nil, true, false, Integer then JSON.generate(item)
      when Float then item.finite? ? JSON.generate(item) : item.to_s.to_json
      else clean_string(item.to_s).to_json
      end
    rescue StandardError
      "#<#{item.class}>".to_json
    end

    # Ensures valid UTF-8 so JSON generation cannot fail on binary or broken strings.
    def clean_string(str)
      return str if str.encoding == Encoding::UTF_8 && str.valid_encoding?

      if str.encoding == Encoding::BINARY || str.encoding == Encoding::UTF_8
        str.dup.force_encoding(Encoding::UTF_8).scrub("�")
      else
        str.encode(Encoding::UTF_8, invalid: :replace, undef: :replace, replace: "�")
      end
    end

    # Cuts to +limit+ characters in total, marking the cut with a trailing ellipsis.
    def truncate(str, limit)
      return str if str.length <= limit

      str[0, limit - 1] + ELLIPSIS
    end

    # ---- process state and fork safety ----

    # Fresh synchronization primitives and an empty queue. Used at construction and in a forked
    # child, where the parent's mutexes, worker thread and queued records must not be reused.
    def reset_state
      @mutex = Mutex.new
      @cv = ConditionVariable.new
      @stop_cv = ConditionVariable.new
      @buffer = []
      @flush_waiters = []
      @stopping = false
      @paused_until = nil
      @dropped = 0
      @thread = nil
      @pid = Process.pid
    end

    # A forked child (Puma cluster, Unicorn, Resque...) inherits this object but not its worker
    # thread. Detected by pid on each call (works on Ruby 3.0, no Process._fork hook needed): the
    # child starts empty (the parent's records are the parent's to send), unpaused, and gets a
    # new worker lazily.
    def check_fork
      return if Process.pid == @pid

      @fork_mutex.synchronize do
        return if Process.pid == @pid

        reset_state
      end
    end

    # Starts the worker when there is none (only in a forked child after construction).
    # @return [Boolean] whether a worker is running (false: nothing to do, none needed)
    def ensure_worker
      return true unless @thread.nil?

      @fork_mutex.synchronize do
        return true unless @thread.nil?
        return false if @mutex.synchronize { @buffer.empty? } && @closed

        start_worker
      end
      true
    end

    def start_worker
      @thread = Thread.new { run }
      @thread.name = "callisto-logger" if @thread.respond_to?(:name=)
      @thread.report_on_exception = false
    end

    # ---- delivery (worker thread) ----

    def paused?
      return false if @paused_until.nil?
      return true if @clock.call.to_i < @paused_until

      @paused_until = nil
      false
    end

    def request_flush(timeout)
      waiter = Waiter.new
      @mutex.synchronize do
        return if @stopping

        @flush_waiters << waiter
        @cv.signal
      end
      waiter.wait(timeout)
    end

    def run
      loop do
        waiters = nil
        stopping = false
        pending = 0
        @mutex.synchronize do
          deadline = Logger.monotonic + @flush_interval
          while !@stopping && @flush_waiters.empty? && @buffer.size < BATCH_TRIGGER
            remaining = deadline - Logger.monotonic
            break if remaining <= 0

            @cv.wait(@mutex, remaining)
          end
          waiters = @flush_waiters
          @flush_waiters = []
          stopping = @stopping
          pending = @buffer.size
        end

        begin
          drain(pending)
        rescue StandardError
          nil
        end
        waiters.each(&:done!)
        next unless stopping

        # Whatever close did not get to send is lost: count it.
        @mutex.synchronize do
          @dropped += @buffer.size
          @buffer.clear
        end
        break
      end
    rescue StandardError
      nil
    end

    # Sends the +count+ records queued when the worker woke up, in batches. Stops early when a
    # retry wait is interrupted by #close.
    def drain(count)
      while count.positive?
        batch = take_batch(count)
        break if batch.empty?

        count -= batch.size
        break if deliver(batch) == :abort
      end
    end

    # At most 500 records and about 1 MB of JSON; always at least one record.
    def take_batch(limit)
      @mutex.synchronize do
        batch = []
        bytes = @prefix.bytesize + @suffix.bytesize
        while !@buffer.empty? && batch.size < [limit, MAX_BATCH_RECORDS].min
          size = @buffer.first.bytesize + 1
          break if !batch.empty? && bytes + size > MAX_BATCH_BYTES

          bytes += size
          batch << @buffer.shift
        end
        batch
      end
    end

    # Sends one batch with retries; every record not delivered is added to the dropped count.
    def deliver(batch)
      outcome = attempt_delivery(batch)
      drop(batch.size) unless outcome == :sent
      outcome
    end

    def drop(count)
      @mutex.synchronize { @dropped += count }
    end

    # @return [Symbol] :sent, :dropped, or :abort (retry wait interrupted by #close)
    def attempt_delivery(batch)
      return :dropped if @mutex.synchronize { paused? }

      body = @prefix + batch.join(",") + @suffix
      attempt = 0
      loop do
        attempt += 1
        status, retry_after = send_once(body)
        delay =
          if status.nil?
            NETWORK_RETRY_DELAYS[attempt - 1]
          elsif status >= 200 && status < 300
            return :sent
          elsif status == 401 || status == 403
            @mutex.synchronize do
              @paused_until = @clock.call.to_i + AUTH_PAUSE_SECONDS * NANOS_PER_SECOND
              @dropped += @buffer.size
              @buffer.clear
            end
            return :dropped
          elsif status == 429 || status == 503
            parse_retry_after(retry_after)
          else
            return :dropped # 400 / 413 / 415 / anything else: drop the batch
          end
        return :dropped if attempt >= MAX_ATTEMPTS || delay.nil?
        return :abort unless wait_before_retry(delay)
      end
    end

    # @return [Array(Integer, String)] or [nil, nil] on a network error
    def send_once(body)
      result = @sender.call(@endpoint, @headers, body)
      case result
      when Integer
        [result, nil]
      when Array
        status = result[0]
        return [nil, nil] unless status.is_a?(Integer)

        [status, header_value(result[1], "retry-after")]
      else
        [nil, nil]
      end
    rescue StandardError
      [nil, nil]
    end

    def header_value(headers, name)
      return nil unless headers.is_a?(Hash)

      headers.each { |k, v| return v if k.to_s.downcase == name }
      nil
    end

    def parse_retry_after(raw)
      seconds = Float(raw.to_s.strip)
      return DEFAULT_RETRY_AFTER if seconds.nan? || seconds.negative?

      [seconds, MAX_RETRY_AFTER].min
    rescue ArgumentError, TypeError
      DEFAULT_RETRY_AFTER
    end

    # @return [Boolean] false when the logger is stopping (the batch is then dropped)
    def wait_before_retry(seconds)
      if @sleeper
        @sleeper.call(seconds)
      else
        @mutex.synchronize { @stop_cv.wait(@mutex, seconds) unless @stopping }
      end
      !@stopping
    end
  end
end
