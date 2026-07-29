package com.mugen.shared.response;

/**
 * Uniform response envelope for successful service responses. Error (4xx/5xx) responses
 * are represented separately via RFC 9457 ProblemDetail from GlobalExceptionHandler —
 * this wrapper's error field exists for cases (e.g. batch/async results) where a partial
 * or non-HTTP-status failure needs to travel alongside a traceId.
 */
public record ApiResponse<T>(T data, ApiError error, String traceId) {

    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(data, null, traceId);
    }

    public static <T> ApiResponse<T> failure(ApiError error, String traceId) {
        return new ApiResponse<>(null, error, traceId);
    }
}
