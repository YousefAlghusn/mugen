package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 422 — the request was well-formed and understood, but a domain rule forbids it.
 * <p>
 * Distinct from a 400: 400 means "this request is malformed, fix the syntax", 422
 * means "the syntax is fine, the operation is not allowed right now". Clients can
 * act on that difference — a 422 message is worth showing to the user, a 400
 * usually indicates a client bug.
 */
@ApiError(code = ErrorCode.BUSINESS_RULE_VIOLATION, status = HttpStatus.UNPROCESSABLE_ENTITY,
        description = "The request is well-formed, but a domain rule forbids it right now.")
public class BusinessRuleException extends AppException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
