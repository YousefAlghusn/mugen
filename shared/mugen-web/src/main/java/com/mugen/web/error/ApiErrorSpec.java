package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * One failure's contract — what a caller receives, and why.
 *
 * @param code        the {@code code} property of the problem document
 * @param status      the HTTP status it is answered with
 * @param description the explanation published in the API document
 */
public record ApiErrorSpec(ErrorCode code, HttpStatus status, String description) {
}
