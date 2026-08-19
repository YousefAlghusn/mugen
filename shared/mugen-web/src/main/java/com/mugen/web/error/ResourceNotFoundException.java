package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 404 — the addressed resource does not exist. */
@ApiError(code = ErrorCode.RESOURCE_NOT_FOUND, status = HttpStatus.NOT_FOUND,
        description = "Nothing exists at the address the request named.")
public class ResourceNotFoundException extends AppException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
