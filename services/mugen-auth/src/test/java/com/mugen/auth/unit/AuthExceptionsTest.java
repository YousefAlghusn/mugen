package com.mugen.auth.unit;

import com.mugen.auth.exception.AuthExceptions;
import com.mugen.shared.error.ErrorCode;
import com.mugen.web.error.ApiError;
import com.mugen.web.error.ApiErrors;
import com.mugen.web.error.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service's failure vocabulary as a whole, which no single exception can check.
 * <p>
 * Both claims here fail silently in production: the document still generates, the
 * responses still work, and only what a caller reads is wrong.
 */
class AuthExceptionsTest {

    private static List<Class<?>> declaredFailures() {
        return Arrays.stream(AuthExceptions.class.getDeclaredClasses())
                .filter(AppException.class::isAssignableFrom)
                .filter(type -> !Modifier.isAbstract(type.getModifiers()))
                .<Class<?>>map(type -> type)
                .toList();
    }

    /**
     * {@code ApiErrorResponsesCustomizer} merges the failures on an endpoint by code, so
     * two exceptions sharing one publish only the first's explanation — the second
     * disappears from the document with nothing to notice it.
     */
    @Test
    @DisplayName("no two failures answer with the same code")
    void codesAreUnique() {
        List<ErrorCode> codes = declaredFailures().stream()
                .map(type -> ApiErrors.of(type.asSubclass(AppException.class)).code())
                .toList();

        assertThat(codes).doesNotHaveDuplicates();
    }

    /**
     * An exception that declares nothing inherits its base's explanation, which is
     * written to cover every conflict rather than this one — so the document answers
     * "the request collides with existing state" where it could name the collision.
     */
    @Test
    @DisplayName("every failure explains itself rather than inheriting a base's wording")
    void eachDeclaresItsOwnDescription() {
        assertThat(declaredFailures()).allSatisfy(type ->
                assertThat(type.getDeclaredAnnotation(ApiError.class))
                        .describedAs("%s must declare its own @ApiError", type.getSimpleName())
                        .isNotNull());
    }
}
