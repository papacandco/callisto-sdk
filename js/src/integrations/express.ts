import type { ErrorRequestHandler, Request } from "express";
import {
  reportRequestError,
  type CallistoIntegrationOptions,
  type ErrorCapturer,
} from "./shared.js";

export type { CallistoIntegrationOptions, ErrorCapturer } from "./shared.js";

function requestPath(req: Request): string {
  // req.route?.path can be a RegExp or string[] for regex/array routes; only
  // use it when it's a plain string, else fall back to the resolved URL.
  const routePath = req.route?.path;
  const path = typeof routePath === "string" ? routePath : undefined;
  return path ?? req.originalUrl ?? req.url ?? "";
}

/**
 * Express error-handling middleware that reports unhandled errors to Callisto
 * and then re-throws by calling `next(err)`. Register it last, immediately
 * before your own error handler.
 *
 * @example
 *   app.use(callistoErrorHandler(client));
 *   app.use(myErrorHandler);
 */
export function callistoErrorHandler(
  client: ErrorCapturer,
  options?: CallistoIntegrationOptions,
): ErrorRequestHandler {
  return (err, req, _res, next) => {
    reportRequestError(
      client,
      err,
      { method: req.method, path: requestPath(req) },
      options,
    );
    next(err);
  };
}
