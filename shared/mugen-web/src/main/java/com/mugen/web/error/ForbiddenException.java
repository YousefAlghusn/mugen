package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 403 — authenticated, but not permitted to do this. */
@ApiError(code = ErrorCode.FORBIDDEN, status = HttpStatus.FORBIDDEN,
        description = "The caller is signed in, but not allowed to do this.")
public class ForbiddenException extends AppException {

    public ForbiddenException(String message) {
        super(message);
    }
}
