import { describe, it, expect } from "vitest";
import {
  errorFromStatus,
  AuthenticationError,
  ValidationError,
  NotFoundError,
  RateLimitError,
  ApiError,
} from "../src/errors.js";

describe("errorFromStatus", () => {
  it("maps statuses to the right class", () => {
    expect(errorFromStatus(401, "nope", {})).toBeInstanceOf(AuthenticationError);
    expect(errorFromStatus(400, "bad", {})).toBeInstanceOf(ValidationError);
    expect(errorFromStatus(422, "bad", {})).toBeInstanceOf(ValidationError);
    expect(errorFromStatus(404, "gone", {})).toBeInstanceOf(NotFoundError);
    expect(errorFromStatus(429, "slow", {})).toBeInstanceOf(RateLimitError);
    expect(errorFromStatus(500, "boom", {})).toBeInstanceOf(ApiError);
  });

  it("carries status code and message", () => {
    const err = errorFromStatus(404, "Message not found", { foo: 1 });
    expect(err.statusCode).toBe(404);
    expect(err.message).toBe("Message not found");
    expect(err.body).toEqual({ foo: 1 });
  });
});
