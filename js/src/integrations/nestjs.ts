import type {
  CallHandler,
  ExecutionContext,
  NestInterceptor,
} from "@nestjs/common";
import type { Observable } from "rxjs";
import { tap } from "rxjs";
import {
  reportRequestError,
  type CallistoIntegrationOptions,
  type ErrorCapturer,
} from "./shared.js";

export type { CallistoIntegrationOptions, ErrorCapturer } from "./shared.js";

interface HttpRequestLike {
  method?: string;
  route?: { path?: string };
  originalUrl?: string;
  url?: string;
}

function extractRequest(context: ExecutionContext): {
  method: string;
  path: string;
} {
  let req: HttpRequestLike | undefined;
  try {
    req = context.switchToHttp().getRequest<HttpRequestLike>();
  } catch {
    req = undefined;
  }
  return {
    method: req?.method ?? "",
    path: req?.route?.path ?? req?.originalUrl ?? req?.url ?? "",
  };
}

/**
 * NestJS interceptor that reports unhandled request errors to Callisto and
 * re-emits them untouched (no response shaping, no interference with your
 * exception filters).
 *
 * @example
 *   app.useGlobalInterceptors(new CallistoInterceptor(client));
 */
export class CallistoInterceptor implements NestInterceptor {
  constructor(
    private readonly client: ErrorCapturer,
    private readonly options?: CallistoIntegrationOptions,
  ) {}

  intercept(
    context: ExecutionContext,
    next: CallHandler,
  ): Observable<unknown> {
    const req = extractRequest(context);
    return next.handle().pipe(
      tap({
        error: (err: unknown) => {
          reportRequestError(this.client, err, req, this.options);
        },
      }),
    );
  }
}
