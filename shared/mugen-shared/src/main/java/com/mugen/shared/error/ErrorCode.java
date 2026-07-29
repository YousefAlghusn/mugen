package com.mugen.shared.error;

/**
 * Machine-readable error codes carried by AppException subclasses (see the
 * exception-handling reference pattern) and surfaced via ApiError / ProblemDetail.
 */
public enum ErrorCode {

    // Generic
    RESOURCE_NOT_FOUND,
    VALIDATION_FAILED,
    CONFLICT,
    BUSINESS_RULE_VIOLATION,
    FORBIDDEN,
    RATE_LIMIT_EXCEEDED,
    INTERNAL_ERROR,

    // Auth (mugen-auth / mugen-gateway)
    USER_NOT_FOUND,
    EMAIL_ALREADY_REGISTERED,
    INVALID_CREDENTIALS,
    TOKEN_EXPIRED,
    TOKEN_INVALID,
    TOKEN_REVOKED,
    SESSION_NOT_FOUND,
    SESSION_REPLAY_DETECTED,

    // User (mugen-user)
    FOLLOW_NOT_FOUND,
    ALREADY_FOLLOWING,

    // Post (mugen-post)
    POST_NOT_FOUND,
    COMMENT_NOT_FOUND,

    // Video (mugen-video / mugen-transcode)
    VIDEO_NOT_FOUND,
    UPLOAD_NOT_FOUND,
    TRANSCODE_FAILED,

    // Payment (mugen-payment)
    PAYMENT_NOT_FOUND,
    PAYMENT_FAILED,
    DUPLICATE_IDEMPOTENCY_KEY,

    // Search (mugen-search)
    SEARCH_QUERY_INVALID
}
