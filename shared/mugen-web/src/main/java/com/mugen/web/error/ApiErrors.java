package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Reads the {@link ApiError} declaration off an exception class. */
public final class ApiErrors {

    /** Namespace for the problem document's {@code type}. Stable, dereferenceable error ids. */
    private static final String TYPE_BASE = "https://mugen.dev/errors/";

    // Resolution walks a class hierarchy and runs on every throw, so it is done once
    // per exception type rather than per instance.
    private static final Map<Class<?>, ApiErrorSpec> SPECS = new ConcurrentHashMap<>();

    private ApiErrors() {
    }

    public static ApiErrorSpec of(Class<? extends AppException> exceptionType) {
        return SPECS.computeIfAbsent(exceptionType, ApiErrors::resolve);
    }

    /** The {@code type} URI for a code, so the document and the thrown response agree on it. */
    public static URI typeUri(ErrorCode code) {
        return URI.create(TYPE_BASE + code.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /**
     * Nearest declaration wins, attribute by attribute — the subclass names the code,
     * its base fixes the status. Spring's merged view is ordered from the class outwards,
     * and an attribute left at its default counts as not declared.
     */
    private static ApiErrorSpec resolve(Class<?> exceptionType) {
        List<MergedAnnotation<ApiError>> declarations = MergedAnnotations
                .from(exceptionType, SearchStrategy.TYPE_HIERARCHY)
                .stream(ApiError.class)
                .toList();

        return new ApiErrorSpec(
                declared(declarations, "code")
                        .map(annotation -> annotation.getEnum("code", ErrorCode.class))
                        .orElse(ErrorCode.INTERNAL_ERROR),
                declared(declarations, "status")
                        .map(annotation -> annotation.getEnum("status", HttpStatus.class))
                        .orElse(HttpStatus.INTERNAL_SERVER_ERROR),
                declared(declarations, "description")
                        .map(annotation -> annotation.getString("description"))
                        .orElse(""));
    }

    private static Optional<MergedAnnotation<ApiError>> declared(
            List<MergedAnnotation<ApiError>> declarations, String attribute) {

        return declarations.stream()
                .filter(annotation -> !annotation.hasDefaultValue(attribute))
                .findFirst();
    }
}
