package com.mugen.shared.response;

import com.mugen.shared.error.ErrorCode;

public record ApiError(ErrorCode code, String message) {
}
