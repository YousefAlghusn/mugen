package com.mugen.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The annotated string must not exceed {@code value} bytes once UTF-8 encoded.
 * <p>
 * {@code @Size} counts characters, but BCrypt hashes only the first 72 <em>bytes</em>
 * — so a char-count cap lets a multi-byte password past the limit it appears to
 * enforce, and BCrypt then either rejects it (a 500) or truncates it (two passwords
 * sharing a 72-byte prefix both authenticate).
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface MaxUtf8Bytes {

    int value();

    String message() default "must not exceed {value} bytes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
