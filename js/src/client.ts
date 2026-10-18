import { resolveConfig, type CallistoOptions } from "./config.js";
import { Transport } from "./http.js";
import { ErrorReporter, type Sender } from "./reporter.js";
import { CallistoLogger, type LogsSender } from "./logger.js";
import { BalanceResource } from "./resources/balance.js";
import { SmsResource } from "./resources/sms.js";
import { OtpResource } from "./resources/otp.js";
import { WhatsAppResource } from "./resources/whatsapp.js";
import { NotifyResource } from "./resources/notify.js";

export interface CallistoClientExtras {
  /** Inject a fake error-reporting sender (tests/advanced use). */
  errorSender?: Sender;
  /** Inject a fake logs sender (tests/advanced use). */
  logsSender?: LogsSender;
  /** Inject a clock (epoch ms) for the logger (tests). */
  logsClock?: () => number;
}

export class CallistoClient {
  readonly balance: BalanceResource;
  readonly sms: SmsResource;
  readonly otp: OtpResource;
  readonly whatsapp: WhatsAppResource;
  readonly notify: NotifyResource;
  /** The error reporter (no-op when no DSN is configured). */
  readonly errorReporter: ErrorReporter;
  /**
   * Log shipping to callisto-logs (no-op when no Logs key resolves). Uses
   * only the Logs key, never `clientId` / `apiKey`.
   */
  readonly logs: CallistoLogger;

  private readonly unhandledHandlers: Array<() => void> = [];

  constructor(
    options: CallistoOptions = {},
    fetchImpl?: typeof fetch,
    extras: CallistoClientExtras = {},
  ) {
    const cfg = resolveConfig(options);
    this.errorReporter = new ErrorReporter(
      {
        dsn: cfg.errorDsn,
        environment: cfg.environment,
        sender: extras.errorSender,
      },
      fetchImpl,
    );
    // Built from the Logs settings only: the messaging credentials never reach it.
    this.logs = new CallistoLogger({
      logsAccessKey: options.logsAccessKey,
      logsSecret: options.logsSecret,
      logsEndpoint: options.logsEndpoint,
      serviceName: options.serviceName,
      environment: options.environment,
      sender: extras.logsSender,
      fetch: fetchImpl,
      clock: extras.logsClock,
    });
    const transport = new Transport(cfg, fetchImpl, this.errorReporter);
    this.balance = new BalanceResource(transport);
    this.sms = new SmsResource(transport);
    this.otp = new OtpResource(transport);
    this.whatsapp = new WhatsAppResource(transport);
    this.notify = new NotifyResource(transport);

    if (cfg.captureUnhandled && this.errorReporter.isEnabled) {
      this.installUnhandledHandlers();
    }
  }

  /** Report a host-application exception. Never throws. */
  captureException(
    error: unknown,
    level?: string,
    extra?: Record<string, unknown> | null,
  ): void {
    this.errorReporter.captureException(error, level, extra);
  }

  /** Report a plain message. Never throws. */
  captureMessage(
    message: string,
    level?: string,
    extra?: Record<string, unknown> | null,
  ): void {
    this.errorReporter.captureMessage(message, level, extra);
  }

  /** Attach a user context to subsequent events (or clear it with null). */
  setUser(user: Record<string, unknown> | null | undefined): void {
    this.errorReporter.setUser(user);
  }

  /**
   * Flush in-flight reports, close the logger (bounded flush) and remove any
   * installed global handlers. Never rejects.
   */
  async close(): Promise<void> {
    for (const remove of this.unhandledHandlers.splice(0)) {
      try {
        remove();
      } catch {
        // ignore
      }
    }
    await Promise.all([this.errorReporter.flush(), this.logs.close()]);
  }

  private installUnhandledHandlers(): void {
    // Guard for non-Node environments (browsers, workers).
    const proc = (globalThis as { process?: NodeJS.Process }).process;
    if (!proc || typeof proc.on !== "function") return;

    const onException = (err: unknown): void => {
      this.errorReporter.captureException(err, "fatal");
    };
    const onRejection = (reason: unknown): void => {
      this.errorReporter.captureException(reason, "fatal");
    };

    // Chaining: process.on appends listeners, preserving existing ones.
    proc.on("uncaughtException", onException);
    proc.on("unhandledRejection", onRejection);

    this.unhandledHandlers.push(() => {
      proc.off?.("uncaughtException", onException);
      proc.off?.("unhandledRejection", onRejection);
    });
  }
}
