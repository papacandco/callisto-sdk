export class CallistoError extends Error {
  readonly statusCode: number;
  readonly body: unknown;

  constructor(message: string, statusCode: number, body: unknown) {
    super(message);
    this.name = new.target.name;
    this.statusCode = statusCode;
    this.body = body;
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

export class AuthenticationError extends CallistoError {}
export class ValidationError extends CallistoError {}
export class NotFoundError extends CallistoError {}
export class RateLimitError extends CallistoError {
  /** Seconds to wait before retrying, parsed from the `Retry-After` header. */
  readonly retryAfter?: number;

  constructor(
    message: string,
    statusCode: number,
    body: unknown,
    retryAfter?: number,
  ) {
    super(message, statusCode, body);
    this.retryAfter = retryAfter;
  }
}
export class ApiError extends CallistoError {}

export class NetworkError extends CallistoError {
  constructor(message: string, cause?: unknown) {
    super(message, 0, undefined);
    this.cause = cause;
  }
}

export function errorFromStatus(
  status: number,
  message: string,
  body: unknown,
  retryAfter?: number,
): CallistoError {
  switch (true) {
    case status === 401:
      return new AuthenticationError(message, status, body);
    case status === 400 || status === 422:
      return new ValidationError(message, status, body);
    case status === 404:
      return new NotFoundError(message, status, body);
    case status === 429:
      return new RateLimitError(message, status, body, retryAfter);
    default:
      return new ApiError(message, status, body);
  }
}
