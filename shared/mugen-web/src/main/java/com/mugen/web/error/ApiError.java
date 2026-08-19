package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a failure is, declared once on the exception class that represents it.
 * <p>
 * The same declaration serves the thrown response and the API document: {@link
 * AppException} reads {@code code} and {@code status} here instead of taking them as
 * constructor arguments, and {@code @Throws} on an endpoint names this class to
 * document the failure. Constructor arguments could not do both — a customizer holds
 * only the exception's {@code Class}, and what a constructor passes to {@code super}
 * is bytecode reflection cannot read.
 * <p>
 * Unset attributes are inherited from the nearest superclass that sets them, so a
 * subclass of {@link ConflictException} states its own {@code code} and explanation
 * and says nothing about 409.
 * <p>
 * <strong>"Unset" means "equal to the default"</strong>, because reflection cannot tell
 * an omitted attribute from one written out with its default value. Writing
 * {@code code = INTERNAL_ERROR} on a subclass therefore inherits the base's code
 * instead, silently. A failure that genuinely is a 500 belongs on a base class where
 * nothing overrides it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiError {

    /** The machine-readable code clients branch on. */
    ErrorCode code() default ErrorCode.INTERNAL_ERROR;

    /** Normally left to the base class — 409 belongs to {@link ConflictException}, not to each conflict. */
    HttpStatus status() default HttpStatus.INTERNAL_SERVER_ERROR;

    /**
     * Why a caller gets this, in the API document. Written for whoever is integrating
     * against the endpoint, so it explains the cause rather than restating the code —
     * this is the one place it is written, however many endpoints can produce it.
     */
    String description() default "";
}
