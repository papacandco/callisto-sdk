# frozen_string_literal: true

require "net/http"
require "uri"
require "json"
require "timeout"

module Callisto
  # Opt-in, Sentry-style error reporter. Captures exceptions/messages and POSTs them to the
  # Callisto error-tracking ingest endpoint (the DSN). Delivery is background, best-effort, and
  # never alters or delays the original error.
  #
  # Isolation guarantees:
  # - Uses its own minimal Net::HTTP path, never the main Transport, so it never recurses and
  #   never inherits the Basic-auth credentials.
  # - Its own send failures (any exception, any non-202) are swallowed and never re-captured.
  # - When the DSN is absent or invalid, every method is a cheap no-op.
  #
  # PII rule (hard requirement): never transmits client_id, api_key, the Authorization header,
  # or the outgoing request body.
  class ErrorReporter
    LEVELS = %w[fatal error warning info].freeze
    DEFAULT_LEVEL = "error"

    # Source lines captured on each side of a frame's error line, and the file
    # size above which source capture is skipped.
    SOURCE_CONTEXT_LINES = 5
    MAX_SOURCE_BYTES = 2_000_000

    # Default HTTP sender: a single-shot Net::HTTP POST returning the integer status code.
    class HttpSender
      def initialize(timeout)
        @timeout = timeout
      end

      # @return [Integer, nil] the HTTP status code, or nil on transport failure.
      def call(url, payload)
        uri = URI.parse(url)
        req = Net::HTTP::Post.new(uri)
        req["Content-Type"] = "application/json"
        req["Accept"] = "application/json"
        req.body = JSON.generate(payload)

        http = Net::HTTP.new(uri.host, uri.port)
        http.use_ssl = uri.scheme == "https"
        http.open_timeout = @timeout
        http.read_timeout = @timeout
        http.write_timeout = @timeout if http.respond_to?(:write_timeout=)

        http.request(req).code.to_i
      rescue StandardError
        nil
      end
    end

    SENTINEL = :__callisto_error_reporter_stop__

    # @param dsn [String, nil] the ingest DSN (full POST URL). Absent/invalid → no-op.
    # @param sdk [Hash] SDK metadata { name:, version:, language: }.
    # @param environment [String, nil] optional environment tag.
    # @param sender [#call, nil] injectable sender; defaults to a real Net::HTTP poster.
    # @param timeout [Float] HTTP timeout for the default sender (seconds).
    def initialize(dsn:, sdk:, environment: nil, sender: nil, timeout: 5.0)
      @dsn = valid_dsn(dsn)
      @sdk = sdk
      @environment = environment
      @sender = sender || HttpSender.new(timeout)
      @user = nil
      @mutex = Mutex.new

      @enabled = !@dsn.nil?
      return unless @enabled

      @queue = Queue.new
      @thread = Thread.new { run }
      @thread.name = "callisto-error-reporter" if @thread.respond_to?(:name=)
    end

    # @return [Boolean] whether reporting is active (a valid DSN was provided).
    def enabled?
      @enabled
    end

    # Captures an exception and enqueues a background send. Never raises.
    #
    # @param error [Exception]
    # @param level [String] one of fatal|error|warning|info
    # @param extra [Hash, nil] extra fields merged into context
    def capture_exception(error, level: DEFAULT_LEVEL, extra: nil)
      return unless @enabled

      payload = build_exception_payload(error, level, extra)
      enqueue(payload)
      nil
    rescue StandardError
      nil
    end

    # Captures a plain message and enqueues a background send. Never raises.
    def capture_message(message, level: "info", extra: nil)
      return unless @enabled

      payload = base_payload(message.to_s, "message", level, extra)
      enqueue(payload)
      nil
    rescue StandardError
      nil
    end

    # Sets (or clears, with nil) the user context attached to subsequent events.
    def set_user(user)
      @mutex.synchronize { @user = user.nil? ? nil : dup_user(user) }
      nil
    rescue StandardError
      nil
    end

    # Drains pending sends with a short bound. Best-effort. The reporter remains usable.
    def flush(timeout: 2.0)
      return unless @enabled
      return if @queue.nil?

      done = Queue.new
      @queue.push([:flush, done])
      begin
        Timeout.timeout(timeout) { done.pop }
      rescue StandardError
        nil
      end
      nil
    rescue StandardError
      nil
    end

    # Flushes and stops the worker thread, joining with a short bound. Best-effort.
    def close(timeout: 2.0)
      return unless @enabled

      flush(timeout: timeout)
      @queue.push(SENTINEL)
      @thread.join(timeout) if @thread
      nil
    rescue StandardError
      nil
    end

    private

    def enqueue(payload)
      @queue.push([:event, payload])
    end

    def run
      loop do
        item = @queue.pop
        break if item == SENTINEL

        kind, value = item
        case kind
        when :flush
          value.push(true)
        when :event
          deliver(value)
        end
      end
    rescue StandardError
      nil
    end

    def deliver(payload)
      @sender.call(@dsn, payload)
    rescue StandardError
      nil
    end

    def valid_dsn(dsn)
      return nil if dsn.nil? || (dsn.is_a?(String) && dsn.strip.empty?)

      uri = URI.parse(dsn)
      return nil unless uri.is_a?(URI::HTTP) && !uri.host.to_s.empty?

      dsn
    rescue StandardError
      nil
    end

    def build_exception_payload(error, level, extra)
      type = error.class.name
      payload = base_payload(error.message.to_s, type, level, extra)

      # Source context only for genuine application exceptions: a transport call
      # site can embed the outgoing request body as literal arguments, which
      # would violate the hard no-request-body guarantee. Transport errors carry
      # @callisto_method / @callisto_path and already use method/path as culprit.
      is_transport = !error.instance_variable_get(:@callisto_method).nil? &&
                     !error.instance_variable_get(:@callisto_path).nil?
      frames = parse_backtrace(error.backtrace, with_source: !is_transport)
      payload[:stacktrace] = frames unless frames.empty?

      culprit, request = culprit_and_request(error, frames)
      payload[:culprit] = culprit if culprit
      payload[:request] = request if request

      add_callisto_context(payload, error)
      payload
    end

    def base_payload(message, type, level, extra)
      payload = {
        message: message,
        type: type,
        level: normalize_level(level),
        context: build_context(extra)
      }
      @mutex.synchronize { payload[:user] = dup_user(@user) unless @user.nil? }
      payload
    end

    def build_context(extra)
      context = { sdk: @sdk.dup }
      context[:environment] = @environment unless @environment.nil?
      context.merge!(extra) if extra.is_a?(Hash)
      context
    end

    def add_callisto_context(payload, error)
      return unless error.is_a?(Callisto::CallistoError)

      ctx = payload[:context]
      ctx[:status_code] = error.status_code if error.respond_to?(:status_code)
      ctx[:retry_after] = error.retry_after if error.respond_to?(:retry_after) && error.retry_after
      ctx[:body] = error.body if error.respond_to?(:body) && !error.body.nil?
    end

    # Transport errors carry @callisto_method / @callisto_path set by the transport hook.
    def culprit_and_request(error, frames)
      method = error.instance_variable_get(:@callisto_method)
      path = error.instance_variable_get(:@callisto_path)

      if method && path
        return ["#{method} #{path}", { method: method, path: path }]
      end

      top = frames.first
      if top
        culprit =
          if top[:function]
            "#{top[:function]} (#{top[:file]}:#{top[:line]})"
          else
            "#{top[:file]}:#{top[:line]}"
          end
        return [culprit, nil]
      end

      [nil, nil]
    end

    # Parses Ruby backtrace lines ("file:line:in `function'") into frame hashes.
    # When with_source is true, each frame whose file is readable also carries a
    # pre_context / context_line / post_context window around its error line.
    def parse_backtrace(backtrace, with_source: false)
      return [] unless backtrace.is_a?(Array)

      backtrace.first(50).map do |line|
        m = line.match(/\A(?<file>.+?):(?<line>\d+)(?::in [`'](?<fn>.*)['`])?\z/)
        frame =
          if m
            { function: m[:fn], file: m[:file], line: m[:line].to_i }
          else
            { function: nil, file: line, line: nil }
          end
        if with_source && frame[:file] && frame[:line]
          ctx = source_context(frame[:file], frame[:line])
          frame.merge!(ctx) if ctx
        end
        frame
      end
    rescue StandardError
      []
    end

    # Best-effort source window around `line` in `file`: up to
    # SOURCE_CONTEXT_LINES lines before (pre_context), the line itself
    # (context_line), and up to SOURCE_CONTEXT_LINES after (post_context). Ruby
    # backtraces carry real file paths, so this works wherever the source is
    # present at runtime. Any unreadable / oversized / out-of-range file yields
    # nil and the frame renders without a window.
    def source_context(file, line)
      return nil if file.nil? || line.nil? || line < 1
      return nil unless File.file?(file) && File.readable?(file)
      return nil if File.size(file) > MAX_SOURCE_BYTES

      lines = File.readlines(file, chomp: true)
      return nil if line > lines.length

      index = line - 1
      start = [index - SOURCE_CONTEXT_LINES, 0].max
      finish = [index + SOURCE_CONTEXT_LINES, lines.length - 1].min
      {
        pre_context: lines[start...index],
        context_line: lines[index],
        post_context: index + 1 <= finish ? lines[(index + 1)..finish] : []
      }
    rescue StandardError
      nil
    end

    def normalize_level(level)
      lvl = level.to_s.downcase
      LEVELS.include?(lvl) ? lvl : DEFAULT_LEVEL
    end

    def dup_user(user)
      return user unless user.respond_to?(:dup)

      user.dup
    rescue StandardError
      user
    end
  end
end
