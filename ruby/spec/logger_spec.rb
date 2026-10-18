# frozen_string_literal: true

module LoggerSpecSupport
  ACCESS_KEY = "pk_00112233445566778899aabbccddeeff"
  SECRET = "sk_000102030405060708090a0b0c0d0e0f1011121314151617"
  ENDPOINT = "https://logs.example.test/v1/logs"
  FIXED_NANOS = 1_760_745_600 * 1_000_000_000
  ENV_KEYS = %w[
    CALLISTO_LOGS_ACCESS_KEY CALLISTO_LOGS_SECRET CALLISTO_LOGS_ENDPOINT
    CALLISTO_SERVICE_NAME OTEL_SERVICE_NAME CALLISTO_ENVIRONMENT
  ].freeze

  # Records every request; replies from a script of responses (the last one repeats).
  # A response is [status, headers], an Integer, :network (returns nil) or :raise.
  class RecordingSender
    attr_reader :calls

    def initialize(*responses)
      @responses = responses.empty? ? [[200, {}]] : responses
      @calls = []
      @mutex = Mutex.new
      @gate = nil
    end

    # Makes the next call block until #release.
    def hold!
      @gate = Queue.new
      @held = @gate
      @entered = Queue.new
    end

    def wait_entered(timeout = 2)
      Timeout.timeout(timeout) { @entered.pop }
    end

    def release
      @held&.push(true)
    end

    def call(url, headers, body)
      @mutex.synchronize { @calls << { url: url, headers: headers, body: body } }
      gate = @gate
      if gate
        @gate = nil
        @entered.push(true)
        gate.pop
      end
      response = @mutex.synchronize { @responses.size > 1 ? @responses.shift : @responses.first }
      raise "connection reset" if response == :raise
      return nil if response == :network

      response
    end

    def payloads
      @mutex.synchronize { @calls.map { |c| JSON.parse(c[:body]) } }
    end

    def records
      payloads.map { |p| p["resourceLogs"][0]["scopeLogs"][0]["logRecords"] }
    end

    def batch_sizes
      records.map(&:size)
    end
  end

  # Mutable fake clock in nanoseconds.
  class FakeClock
    attr_accessor :nanos

    def initialize(nanos = FIXED_NANOS)
      @nanos = nanos
    end

    def call
      @nanos
    end

    def advance(seconds)
      @nanos += seconds * 1_000_000_000
    end
  end

  def build_logger(sender: RecordingSender.new, clock: FakeClock.new, delays: [], **opts)
    defaults = {
      logs_access_key: ACCESS_KEY, logs_secret: SECRET, logs_endpoint: ENDPOINT,
      service_name: "checkout", environment: "production",
      sender: sender, clock: clock, sleeper: ->(s) { delays << s },
      flush_interval: 60
    }
    logger = Callisto::Logger.new(**defaults.merge(opts))
    (@loggers ||= []) << logger
    logger
  end

  def wait_until(timeout = 2)
    deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + timeout
    until yield
      raise "condition not met within #{timeout}s" if Process.clock_gettime(Process::CLOCK_MONOTONIC) > deadline

      sleep 0.005
    end
  end

  def record_attrs(record)
    (record["attributes"] || []).each_with_object({}) { |kv, h| h[kv["key"]] = kv["value"] }
  end
end

RSpec.describe Callisto::Logger do
  include LoggerSpecSupport

  around do |example|
    saved = LoggerSpecSupport::ENV_KEYS.to_h { |k| [k, ENV[k]] }
    LoggerSpecSupport::ENV_KEYS.each { |k| ENV.delete(k) }
    begin
      example.run
    ensure
      (@loggers || []).each(&:close)
      saved.each { |k, v| v.nil? ? ENV.delete(k) : ENV[k] = v }
    end
  end

  let(:golden) do
    {
      "resourceLogs" => [{
        "resource" => { "attributes" => [
          { "key" => "service.name", "value" => { "stringValue" => "checkout" } },
          { "key" => "deployment.environment.name", "value" => { "stringValue" => "production" } },
          { "key" => "telemetry.sdk.name", "value" => { "stringValue" => "callisto-sdk" } },
          { "key" => "telemetry.sdk.language", "value" => { "stringValue" => "ruby" } },
          { "key" => "telemetry.sdk.version", "value" => { "stringValue" => Callisto::VERSION } }
        ] },
        "scopeLogs" => [{
          "scope" => { "name" => "callisto-sdk", "version" => Callisto::VERSION },
          "logRecords" => [{
            "timeUnixNano" => "1760745600000000000",
            "observedTimeUnixNano" => "1760745600000000000",
            "severityNumber" => 9,
            "severityText" => "INFO",
            "body" => { "stringValue" => "order paid" },
            "attributes" => [
              { "key" => "order_id", "value" => { "intValue" => "42" } },
              { "key" => "paid", "value" => { "boolValue" => true } }
            ],
            "traceId" => "5b8efff798038103d269b633813fc60c",
            "spanId" => "eee19b7ec3c1b174"
          }]
        }]
      }]
    }
  end

  it "uses version 0.2.0" do
    expect(Callisto::VERSION).to eq("0.2.0")
  end

  describe "wire format" do
    it "produces the golden payload" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)

      logger.info("order paid", { order_id: 42, paid: true,
                                  trace_id: "5B8EFFF798038103D269B633813FC60C",
                                  span_id: "EEE19B7EC3C1B174" })
      logger.flush

      expect(sender.calls.size).to eq(1)
      expect(sender.payloads.first).to eq(golden)
    end

    it "omits deployment.environment.name without an environment and falls back to unknown_service" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender, environment: nil, service_name: nil)
      logger.info("x")
      logger.flush

      attrs = sender.payloads.first["resourceLogs"][0]["resource"]["attributes"].map { |a| a["key"] }
      expect(attrs).not_to include("deployment.environment.name")
      service = sender.payloads.first["resourceLogs"][0]["resource"]["attributes"][0]
      expect(service).to eq({ "key" => "service.name", "value" => { "stringValue" => "unknown_service" } })
    end

    it "sends Basic auth of the shared vector, JSON and the User-Agent to the configured endpoint" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)
      logger.info("hello")
      logger.flush

      call = sender.calls.first
      expect(call[:url]).to eq(LoggerSpecSupport::ENDPOINT)
      expected = "Basic " + ["#{LoggerSpecSupport::ACCESS_KEY}:#{LoggerSpecSupport::SECRET}"].pack("m0")
      expect(call[:headers]["Authorization"]).to eq(expected)
      expect(call[:headers]["Authorization"]).to eq(
        "Basic cGtfMDAxMTIyMzM0NDU1NjY3Nzg4OTlhYWJiY2NkZGVlZmY6c2tfMDAwMTAyMDMwNDA1MDYwNzA4MDkwYTBiMGMwZDBlMGYxMDExMTIxMzE0MTUxNjE3"
      )
      expect(call[:headers]["Content-Type"]).to eq("application/json")
      expect(call[:headers]["User-Agent"]).to eq("callisto-sdk-ruby/0.2.0")
    end

    it "maps the five levels to OTLP severities, via the helpers and log()" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)
      logger.debug("d")
      logger.info("i")
      logger.warn("w")
      logger.error("e")
      logger.fatal("f")
      logger.log(:warning, "alias")
      logger.log("ERROR", "upper")
      logger.log("nope", "unknown")
      logger.flush

      got = sender.records.flatten.map { |r| [r["severityNumber"], r["severityText"]] }
      expect(got).to eq([[5, "DEBUG"], [9, "INFO"], [13, "WARN"], [17, "ERROR"], [21, "FATAL"],
                         [13, "WARN"], [17, "ERROR"], [9, "INFO"]])
    end

    it "maps attributes to AnyValue" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)
      logger.info(12_345, {
        "int" => 7, "neg" => -3, "big" => 2**70, "float" => 1.5,
        "nan" => Float::NAN, "inf" => Float::INFINITY, "ninf" => -Float::INFINITY,
        "str" => "s", "sym" => :ok, "nil" => nil, "f" => false,
        "list" => [1, "a", nil, [true]],
        "map" => { "k" => 2, "drop" => nil, "inner" => { "x" => 0.25 } },
        "obj" => Object.new.tap { |o| def o.to_s = "custom" }
      })
      logger.flush

      record = sender.records.first.first
      expect(record["body"]).to eq({ "stringValue" => "12345" })
      attrs = record_attrs(record)
      expect(attrs["int"]).to eq({ "intValue" => "7" })
      expect(attrs["neg"]).to eq({ "intValue" => "-3" })
      expect(attrs["big"]).to eq({ "stringValue" => (2**70).to_s })
      expect(attrs["float"]).to eq({ "doubleValue" => 1.5 })
      expect(attrs["nan"]).to eq({ "stringValue" => "NaN" })
      expect(attrs["inf"]).to eq({ "stringValue" => "Infinity" })
      expect(attrs["ninf"]).to eq({ "stringValue" => "-Infinity" })
      expect(attrs["str"]).to eq({ "stringValue" => "s" })
      expect(attrs["sym"]).to eq({ "stringValue" => "ok" })
      expect(attrs).not_to have_key("nil")
      expect(attrs["f"]).to eq({ "boolValue" => false })
      expect(attrs["list"]).to eq({ "arrayValue" => { "values" => [
        { "intValue" => "1" }, { "stringValue" => "a" }, {},
        { "arrayValue" => { "values" => [{ "boolValue" => true }] } }
      ] } })
      expect(attrs["map"]).to eq({ "kvlistValue" => { "values" => [
        { "key" => "k", "value" => { "intValue" => "2" } },
        { "key" => "inner", "value" => { "kvlistValue" => { "values" => [
          { "key" => "x", "value" => { "doubleValue" => 0.25 } }
        ] } } }
      ] } })
      expect(attrs["obj"]).to eq({ "stringValue" => "custom" })
    end

    it "stringifies nesting deeper than 5 levels" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)
      # The l1 value is level 1; the map under l6 would be level 6.
      deep = { "l2" => { "l3" => { "l4" => { "l5" => { "l6" => { "l7" => "x" } } } } } }
      logger.info("deep", { "l1" => deep })
      logger.flush

      value = record_attrs(sender.records.first.first)["l1"]
      4.times { value = value["kvlistValue"]["values"][0]["value"] }
      expect(value["kvlistValue"]["values"][0]).to eq(
        { "key" => "l6", "value" => { "stringValue" => '{"l7":"x"}' } }
      )
    end

    it "omits an empty attributes list and absent trace ids" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)
      logger.info("bare")
      logger.flush

      record = sender.records.first.first
      expect(record.keys).not_to include("attributes", "traceId", "spanId")
    end

    it "keeps an invalid trace_id / span_id as ordinary attributes" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)
      logger.info("a", { trace_id: "not-a-trace", span_id: "0000000000000000" })
      logger.info("b", { "trace_id" => "0" * 32, "span_id" => "abc" })
      logger.info("c", { "trace_id" => "5b8efff798038103d269b633813fc60c" })
      logger.flush

      a, b, c = sender.records.first
      expect(a.keys).not_to include("traceId", "spanId")
      expect(record_attrs(a)["trace_id"]).to eq({ "stringValue" => "not-a-trace" })
      expect(record_attrs(a)["span_id"]).to eq({ "stringValue" => "0000000000000000" })
      expect(b.keys).not_to include("traceId", "spanId")
      expect(record_attrs(b).keys).to eq(%w[trace_id span_id])
      expect(c["traceId"]).to eq("5b8efff798038103d269b633813fc60c")
      expect(c).not_to have_key("attributes")
    end

    it "truncates the body and string attribute values with a trailing ellipsis" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)
      logger.info("b" * 40_000, { "long" => "v" * 9_000, "nested" => ["w" * 9_000], "short" => "ok" })
      logger.flush

      record = sender.records.first.first
      body = record["body"]["stringValue"]
      expect(body.length).to eq(32_768)
      expect(body).to end_with("b…")
      attrs = record_attrs(record)
      expect(attrs["long"]["stringValue"].length).to eq(8_192)
      expect(attrs["long"]["stringValue"]).to end_with("…")
      expect(attrs["nested"]["arrayValue"]["values"][0]["stringValue"].length).to eq(8_192)
      expect(attrs["short"]["stringValue"]).to eq("ok")
    end

    it "keeps at most 128 attributes per record" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)
      logger.info("many", (1..200).to_h { |i| ["k#{i}", i] })
      logger.flush

      attrs = sender.records.first.first["attributes"]
      expect(attrs.size).to eq(128)
      expect(attrs.last["key"]).to eq("k128")
    end

    it "never raises from a log call, even on hostile input" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)
      bad = Object.new
      def bad.to_s = raise("boom")

      expect { logger.info(bad) }.not_to raise_error
      expect { logger.info("x", bad) }.not_to raise_error
      expect { logger.info("bin", { "b" => "\xFF\xFE".b }) }.not_to raise_error
      logger.flush
      expect(sender.records.flatten.map { |r| r["body"]["stringValue"] }).to eq(%w[x bin])
    end
  end

  describe "batching" do
    it "sends a batch once 100 records are queued, and flush sends the rest" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)

      100.times { |i| logger.info("r#{i}") }
      wait_until { sender.calls.size == 1 }
      expect(sender.batch_sizes).to eq([100])

      50.times { |i| logger.info("s#{i}") }
      logger.flush
      expect(sender.batch_sizes).to eq([100, 50])
    end

    it "sends on the timer when fewer than 100 records are queued" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender, flush_interval: 0.05)
      logger.info("timer")
      wait_until { sender.calls.size == 1 }
      expect(sender.batch_sizes).to eq([1])
    end

    it "cuts batches at 500 records" do
      sender = LoggerSpecSupport::RecordingSender.new
      sender.hold!
      logger = build_logger(sender: sender)

      100.times { logger.info("first") }
      sender.wait_entered
      1_100.times { logger.info("more") }
      sender.release
      logger.flush

      expect(sender.batch_sizes).to eq([100, 500, 500, 100])
      expect(logger.dropped_count).to eq(0)
    end

    it "cuts batches at about 1 MB of JSON" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)
      90.times { logger.info("x" * 30_000) }
      logger.flush

      expect(sender.batch_sizes.sum).to eq(90)
      expect(sender.batch_sizes.size).to be > 1
      sender.calls.each { |c| expect(c[:body].bytesize).to be <= 1_000_000 }
    end
  end

  describe "delivery outcomes" do
    it "drops a batch on 400 without retrying" do
      sender = LoggerSpecSupport::RecordingSender.new([400, {}])
      delays = []
      logger = build_logger(sender: sender, delays: delays)
      logger.info("bad")
      logger.flush

      expect(sender.calls.size).to eq(1)
      expect(delays).to eq([])
      expect(logger.dropped_count).to eq(1)
    end

    [413, 415, 500].each do |status|
      it "drops and counts a batch rejected with #{status}" do
        sender = LoggerSpecSupport::RecordingSender.new([status, {}])
        logger = build_logger(sender: sender)
        3.times { logger.info("rejected") }
        logger.flush

        expect(sender.calls.size).to eq(1)
        expect(logger.dropped_count).to eq(3)
      end
    end

    it "retries a 429 after Retry-After, then succeeds" do
      sender = LoggerSpecSupport::RecordingSender.new([429, { "Retry-After" => "1" }], [200, {}])
      delays = []
      logger = build_logger(sender: sender, delays: delays)
      logger.info("again")
      logger.flush

      expect(sender.calls.size).to eq(2)
      expect(delays).to eq([1.0])
      expect(sender.calls[0][:body]).to eq(sender.calls[1][:body])
      expect(logger.dropped_count).to eq(0)
    end

    it "caps Retry-After at 60 s and defaults to 5 s when unparsable" do
      sender = LoggerSpecSupport::RecordingSender.new(
        [503, { "retry-after" => "600" }], [503, { "Retry-After" => "soon" }], [200, {}]
      )
      delays = []
      logger = build_logger(sender: sender, delays: delays)
      logger.info("x")
      logger.flush

      expect(delays).to eq([60.0, 5.0])
      expect(sender.calls.size).to eq(3)
    end

    it "drops a batch after 3 failed attempts and keeps sending later batches" do
      sender = LoggerSpecSupport::RecordingSender.new([503, {}])
      delays = []
      logger = build_logger(sender: sender, delays: delays)
      logger.info("lost")
      logger.flush
      expect(sender.calls.size).to eq(3)
      expect(delays).to eq([5.0, 5.0])
      expect(logger.dropped_count).to eq(1)

      logger.info("next")
      logger.info("next too")
      logger.flush
      expect(sender.calls.size).to eq(6)
      expect(sender.records.last.first["body"]["stringValue"]).to eq("next")
      expect(logger.dropped_count).to eq(3)
    end

    it "retries network errors after 1 s then 5 s" do
      sender = LoggerSpecSupport::RecordingSender.new(:network, :raise, :network)
      delays = []
      logger = build_logger(sender: sender, delays: delays)
      logger.info("offline")
      logger.flush

      expect(sender.calls.size).to eq(3)
      expect(delays).to eq([1.0, 5.0])
      expect(logger.dropped_count).to eq(1)
    end

    [401, 403].each do |status|
      it "pauses sending 60 s after a #{status}, then allows one new attempt" do
        sender = LoggerSpecSupport::RecordingSender.new([status, {}])
        clock = LoggerSpecSupport::FakeClock.new
        delays = []
        logger = build_logger(sender: sender, clock: clock, delays: delays)

        logger.info("refused")
        logger.flush
        expect(sender.calls.size).to eq(1)
        expect(logger.dropped_count).to eq(1)

        clock.advance(30)
        logger.info("during pause")
        logger.info("during pause too")
        logger.flush
        expect(sender.calls.size).to eq(1)
        expect(logger.dropped_count).to eq(3)

        clock.advance(31)
        logger.info("after pause")
        logger.flush
        expect(sender.calls.size).to eq(2)
        expect(sender.records.last.map { |r| r["body"]["stringValue"] }).to eq(["after pause"])
        expect(delays).to eq([])
        expect(logger.dropped_count).to eq(4)
      end
    end

    it "counts the records queued behind a batch refused with 401" do
      sender = LoggerSpecSupport::RecordingSender.new([401, {}])
      sender.hold!
      logger = build_logger(sender: sender)

      100.times { logger.info("first") }
      sender.wait_entered
      10.times { logger.info("queued") }
      sender.release
      logger.flush

      expect(sender.calls.size).to eq(1)
      expect(logger.dropped_count).to eq(110)
    end

    it "drops new records and counts them when the queue is full" do
      sender = LoggerSpecSupport::RecordingSender.new
      sender.hold!
      logger = build_logger(sender: sender)

      100.times { logger.info("first") }
      sender.wait_entered
      2_005.times { logger.info("fill") }
      expect(logger.dropped_count).to eq(5)

      sender.release
      logger.flush
      expect(sender.batch_sizes.sum).to eq(2_100)
      expect(logger.dropped_count).to eq(5)
    end

    it "bounds flush and close by flush_timeout even when the sender hangs" do
      sender = LoggerSpecSupport::RecordingSender.new
      sender.hold!
      logger = build_logger(sender: sender, flush_timeout: 0.2)
      logger.info("stuck")

      started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
      logger.flush
      logger.close
      elapsed = Process.clock_gettime(Process::CLOCK_MONOTONIC) - started
      expect(elapsed).to be < 1.0
    ensure
      sender&.release
    end

    it "close flushes, stops the thread and makes later calls no-ops" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)
      logger.info("before close")
      thread = logger.instance_variable_get(:@thread)
      logger.close

      expect(sender.batch_sizes).to eq([1])
      expect(thread.alive?).to be(false)
      expect { logger.info("after"); logger.flush; logger.close }.not_to raise_error
      expect(sender.calls.size).to eq(1)
    end
  end

  describe "fork safety" do
    # Simulates a fork without forking: the parent's worker thread does not survive into the
    # child, and Process.pid changes.
    def simulate_fork(logger)
      parent_thread = logger.instance_variable_get(:@thread)
      parent_thread.kill
      parent_thread.join
      child_pid = Process.pid + 1
      allow(Process).to receive(:pid).and_return(child_pid)
      parent_thread
    end

    it "starts the child with an empty queue and a new worker, and never resends the parent's records" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)
      logger.info("parent 1")
      logger.info("parent 2")
      parent_mutex = logger.instance_variable_get(:@mutex)
      parent_thread = simulate_fork(logger)

      expect(sender.calls).to be_empty
      logger.info("child")
      child_thread = logger.instance_variable_get(:@thread)
      expect(child_thread).not_to equal(parent_thread)
      expect(child_thread.alive?).to be(true)
      expect(logger.instance_variable_get(:@mutex)).not_to equal(parent_mutex)

      logger.flush
      expect(sender.records.flatten.map { |r| r["body"]["stringValue"] }).to eq(["child"])
      logger.close
      expect(child_thread.alive?).to be(false)
    end

    it "resets the pause and the dropped count in the child" do
      sender = LoggerSpecSupport::RecordingSender.new([401, {}], [200, {}])
      logger = build_logger(sender: sender)
      logger.info("refused")
      logger.flush
      logger.info("dropped during pause")
      expect(logger.dropped_count).to eq(2)

      simulate_fork(logger)
      expect(logger.dropped_count).to eq(0)
      expect(logger.instance_variable_get(:@thread)).to be_nil # started lazily
      logger.info("child")
      expect(logger.dropped_count).to eq(0)
      logger.flush

      expect(sender.calls.size).to eq(2)
      expect(sender.records.last.map { |r| r["body"]["stringValue"] }).to eq(["child"])
      logger.close
    end

    it "starts no worker in a child that only closes the logger" do
      sender = LoggerSpecSupport::RecordingSender.new
      logger = build_logger(sender: sender)
      simulate_fork(logger)

      logger.close
      expect(logger.instance_variable_get(:@thread)).to be_nil
      expect(sender.calls).to be_empty
    end
  end

  describe "configuration" do
    it "is disabled without credentials: no thread, no send" do
      before = Thread.list.size
      logger = Callisto::Logger.new(sender: ->(*) { raise "must not send" })

      expect(logger.enabled?).to be(false)
      expect(logger.instance_variable_get(:@thread)).to be_nil
      expect(Thread.list.size).to eq(before)
      expect { logger.info("x"); logger.flush; logger.close }.not_to raise_error
      expect(logger.dropped_count).to eq(0)
    end

    it "resolves credentials, endpoint, service and environment from the environment" do
      ENV["CALLISTO_LOGS_ACCESS_KEY"] = LoggerSpecSupport::ACCESS_KEY
      ENV["CALLISTO_LOGS_SECRET"] = LoggerSpecSupport::SECRET
      ENV["CALLISTO_LOGS_ENDPOINT"] = "http://localhost:4318/v1/logs"
      ENV["OTEL_SERVICE_NAME"] = "otel-name"
      ENV["CALLISTO_ENVIRONMENT"] = "staging"
      sender = LoggerSpecSupport::RecordingSender.new
      logger = Callisto::Logger.new(sender: sender, clock: LoggerSpecSupport::FakeClock.new)
      (@loggers ||= []) << logger

      expect(logger.enabled?).to be(true)
      expect(logger.endpoint).to eq("http://localhost:4318/v1/logs")
      expect(logger.service_name).to eq("otel-name")
      expect(logger.environment).to eq("staging")

      ENV["CALLISTO_SERVICE_NAME"] = "callisto-name"
      expect(Callisto::Logger.new.tap { |l| @loggers << l }.service_name).to eq("callisto-name")
    end

    it "defaults the endpoint to callisto-logs" do
      logger = build_logger(logs_endpoint: nil)
      expect(logger.endpoint).to eq("https://logs.callistosignal.com/v1/logs")
    end

    {
      "only the access key" => { logs_access_key: LoggerSpecSupport::ACCESS_KEY },
      "only the secret" => { logs_secret: LoggerSpecSupport::SECRET },
      "a malformed access key" => { logs_access_key: "pk_123", logs_secret: LoggerSpecSupport::SECRET },
      "an uppercase access key" => { logs_access_key: LoggerSpecSupport::ACCESS_KEY.upcase.sub("PK_", "pk_"),
                                     logs_secret: LoggerSpecSupport::SECRET },
      "a malformed secret" => { logs_access_key: LoggerSpecSupport::ACCESS_KEY, logs_secret: "sk_nope" },
      "swapped key and secret" => { logs_access_key: LoggerSpecSupport::SECRET,
                                    logs_secret: LoggerSpecSupport::ACCESS_KEY },
      "a relative endpoint" => { logs_access_key: LoggerSpecSupport::ACCESS_KEY,
                                 logs_secret: LoggerSpecSupport::SECRET, logs_endpoint: "/v1/logs" },
      "a non-http endpoint" => { logs_access_key: LoggerSpecSupport::ACCESS_KEY,
                                 logs_secret: LoggerSpecSupport::SECRET, logs_endpoint: "ftp://x/v1/logs" }
    }.each do |label, args|
      it "raises Callisto::ValidationError on #{label}" do
        expect { Callisto::Logger.new(**args) }.to raise_error(Callisto::ValidationError)
      end
    end

    it "raises on half credentials coming from the environment" do
      ENV["CALLISTO_LOGS_SECRET"] = LoggerSpecSupport::SECRET
      expect { Callisto::Logger.new }.to raise_error(Callisto::ValidationError, /together/)
    end

    it "does not shadow Ruby's standard library Logger at the top level" do
      require "logger"
      expect(::Logger).not_to eq(Callisto::Logger)
      expect(::Logger.new(IO::NULL)).to respond_to(:formatter)
    end
  end

  describe "on the client" do
    let(:messaging_url) { "https://api.callistosignal.com/v1/sms/balance?format=full" }

    it "is a no-op logger when no Logs key resolves" do
      client = Callisto::Client.new(client_id: "test-client", api_key: "test-key")
      expect(client.logs).to be_a(Callisto::Logger)
      expect(client.logs.enabled?).to be(false)
      expect { client.logs.info("x") }.not_to raise_error
      client.close
    end

    it "raises a configuration error on half Logs credentials" do
      expect do
        Callisto::Client.new(client_id: "test-client", api_key: "test-key",
                             logs_access_key: LoggerSpecSupport::ACCESS_KEY)
      end.to raise_error(Callisto::ValidationError)
    end

    it "keeps messaging and Logs credentials apart, and close flushes the logger" do
      logs_requests = []
      stub_request(:post, LoggerSpecSupport::ENDPOINT).to_return do |req|
        logs_requests << req
        { status: 200, body: "{}" }
      end
      messaging_requests = []
      stub_request(:get, messaging_url).to_return do |req|
        messaging_requests << req
        { status: 200, body: { credit: 1, currency: "XOF" }.to_json,
          headers: { "Content-Type" => "application/json" } }
      end

      client = Callisto::Client.new(
        client_id: "test-client", api_key: "test-key",
        logs_access_key: LoggerSpecSupport::ACCESS_KEY, logs_secret: LoggerSpecSupport::SECRET,
        logs_endpoint: LoggerSpecSupport::ENDPOINT, service_name: "checkout",
        environment: "production"
      )
      client.balance.get
      client.logs.info("order paid", { order_id: 42 })
      client.close

      expect(logs_requests.size).to eq(1)
      logs_req = logs_requests.first
      expected_auth = "Basic " + ["#{LoggerSpecSupport::ACCESS_KEY}:#{LoggerSpecSupport::SECRET}"].pack("m0")
      expect(logs_req.headers["Authorization"]).to eq(expected_auth)
      expect(logs_req.headers["User-Agent"]).to eq("callisto-sdk-ruby/#{Callisto::VERSION}")
      messaging_auth = "Basic " + ["test-client:test-key"].pack("m0")
      logs_dump = logs_req.headers.to_a.flatten.join("\n") + logs_req.body.to_s
      expect(logs_dump).not_to include("test-client", "test-key", messaging_auth)
      body = JSON.parse(logs_req.body)
      resource = body["resourceLogs"][0]["resource"]["attributes"]
      expect(resource).to include({ "key" => "deployment.environment.name",
                                    "value" => { "stringValue" => "production" } })

      expect(messaging_requests.size).to eq(1)
      msg_req = messaging_requests.first
      msg_dump = msg_req.headers.to_a.flatten.join("\n") + msg_req.body.to_s + msg_req.uri.to_s
      expect(msg_dump).not_to include(LoggerSpecSupport::SECRET, LoggerSpecSupport::ACCESS_KEY, expected_auth)
      expect(client.logs.instance_variable_get(:@thread).alive?).to be(false)
    end
  end
end
