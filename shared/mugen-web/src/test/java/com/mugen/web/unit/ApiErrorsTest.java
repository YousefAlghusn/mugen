package com.mugen.web.unit;

import com.mugen.shared.error.ErrorCode;
import com.mugen.web.error.ApiError;
import com.mugen.web.error.ApiErrorSpec;
import com.mugen.web.error.ApiErrors;
import com.mugen.web.error.ConflictException;
import com.mugen.web.error.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolving a failure's contract from the class that declares it — the mechanism that
 * lets one declaration answer a caller and document every endpoint that can produce it.
 */
class ApiErrorsTest {

    @ApiError(code = ErrorCode.EMAIL_ALREADY_REGISTERED, description = "That address already has an account.")
    static class EmailAlreadyRegistered extends ConflictException {
        EmailAlreadyRegistered() {
            super("Email is already registered.");
        }
    }

    static class UnannotatedConflict extends ConflictException {
        UnannotatedConflict() {
            super("Something collided.");
        }
    }

    @Test
    @DisplayName("a subclass names its code and inherits the status its base fixes")
    void mergesAcrossTheHierarchy() {
        ApiErrorSpec spec = ApiErrors.of(EmailAlreadyRegistered.class);

        assertThat(spec.code()).isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
        // 409 is ConflictException's, stated once there rather than on each conflict.
        assertThat(spec.status().value()).isEqualTo(409);
        assertThat(spec.description()).isEqualTo("That address already has an account.");
    }

    @Test
    @DisplayName("an unannotated subclass falls back to its base's declaration")
    void inheritsEverythingWhenNothingIsDeclared() {
        ApiErrorSpec spec = ApiErrors.of(UnannotatedConflict.class);

        assertThat(spec.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(spec.status().value()).isEqualTo(409);
        assertThat(spec.description()).isNotBlank();
    }

    @Test
    @DisplayName("the thrown exception carries what the annotation declares")
    void theExceptionAgreesWithItsDeclaration() {
        EmailAlreadyRegistered thrown = new EmailAlreadyRegistered();

        // The point of the design: the response a caller gets and the documentation
        // they read cannot disagree, because neither is written twice.
        assertThat(thrown.getErrorCode()).isEqualTo(ApiErrors.of(EmailAlreadyRegistered.class).code());
        assertThat(thrown.getStatus()).isEqualTo(ApiErrors.of(EmailAlreadyRegistered.class).status());
    }

    @Test
    @DisplayName("each base class fixes its own status")
    void basesDeclareTheirStatus() {
        assertThat(ApiErrors.of(UnauthorizedException.class).status().value()).isEqualTo(401);
        assertThat(ApiErrors.of(ConflictException.class).status().value()).isEqualTo(409);
    }

    /**
     * Pinned because it is a trap, not a feature: reflection cannot distinguish an
     * omitted attribute from one written with its default value, so a subclass
     * declaring {@code INTERNAL_ERROR} inherits its base's code instead. Anyone
     * "fixing" this will fail here and read why in {@code @ApiError}'s javadoc.
     */
    @Test
    @DisplayName("a value equal to the default reads as unset, and inherits")
    void aDefaultValuedAttributeCountsAsUndeclared() {
        ApiErrorSpec spec = ApiErrors.of(ExplicitlyInternal.class);

        assertThat(spec.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(spec.status().value()).isEqualTo(409);
    }

    @ApiError(code = ErrorCode.INTERNAL_ERROR, description = "A conflict that names the default code.")
    static class ExplicitlyInternal extends ConflictException {
        ExplicitlyInternal() {
            super("Something collided.");
        }
    }

    @Test
    @DisplayName("the type URI is derived from the code, so the document and the response agree")
    void derivesTheTypeUri() {
        assertThat(ApiErrors.typeUri(ErrorCode.EMAIL_ALREADY_REGISTERED))
                .hasToString("https://mugen.dev/errors/email-already-registered");
    }
}
