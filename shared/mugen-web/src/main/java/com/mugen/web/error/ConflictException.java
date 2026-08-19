package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 409 — the request collides with existing state, e.g. a taken email. */
@ApiError(code = ErrorCode.CONFLICT, status = HttpStatus.CONFLICT,
        description = "The request collides with state that already exists.")
public class ConflictException extends AppException {

    public ConflictException(String message) {
        super(message);
    }
}
