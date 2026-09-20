package com.mugen.gateway.error;

import com.mugen.shared.error.ErrorCode;
import com.mugen.web.error.ApiError;
import com.mugen.web.error.AppException;
import org.springframework.http.HttpStatus;

/** The gateway's own failures, on top of the shared hierarchy in mugen-web. */
public final class GatewayExceptions {

    private GatewayExceptions() {
    }

    @ApiError(code = ErrorCode.RATE_LIMIT_EXCEEDED, status = HttpStatus.TOO_MANY_REQUESTS,
            description = "Too many requests from this caller on this route. Back off and retry.")
    public static class RateLimitExceeded extends AppException {
        public RateLimitExceeded() {
            super("Too many requests. Slow down and try again shortly.");
        }
    }

    /** Never names the cause: which instance was down, or how, is operational detail. */
    @ApiError(code = ErrorCode.SERVICE_UNAVAILABLE, status = HttpStatus.SERVICE_UNAVAILABLE,
            description = "The service behind this route could not be reached: no instance, a timeout, or "
                    + "an open circuit. Retry later.")
    public static class ServiceUnavailable extends AppException {
        public ServiceUnavailable(String service) {
            super("%s is temporarily unavailable. Try again shortly.".formatted(service));
        }
    }

    @ApiError(code = ErrorCode.RESOURCE_NOT_FOUND, status = HttpStatus.NOT_FOUND,
            description = "No route serves this path.")
    public static class RouteNotFound extends AppException {
        public RouteNotFound() {
            super("No such endpoint.");
        }
    }
}
