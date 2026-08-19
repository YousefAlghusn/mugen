package com.mugen.web.openapi;

import com.mugen.shared.error.ErrorCode;
import com.mugen.web.error.ApiErrorSpec;
import com.mugen.web.error.ApiErrors;
import com.mugen.web.error.AppException;
import com.mugen.web.error.TokenInvalidException;
import com.mugen.web.security.PublicEndpoints;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Documents every failure an operation can answer with, from the exceptions it declares.
 * <p>
 * An endpoint lists exception classes with {@link Throws}; each one's status, code and
 * explanation come from its {@code @ApiError}. Failures sharing a status become one
 * response listing every code under it, so the document says which errors are possible
 * and what each means without any endpoint restating either.
 * <p>
 * The two failures every endpoint of a kind shares are added without being asked for:
 * validation on anything taking a checked body, and the token rejection on anything not
 * {@code @PublicEndpoint}. A status the operation already declares itself is left alone.
 * <p>
 * Each code also contributes a worked example, so the reader sees the body they will
 * actually receive rather than the schema's {@code "string"} placeholders.
 */
public class ApiErrorResponsesCustomizer implements GlobalOperationCustomizer {

    private static final ApiErrorSpec VALIDATION = new ApiErrorSpec(
            ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
            "The request body failed validation. `errors[]` names every offending field.");

    /** Shaped like a real one, so nobody reads it as a format to parse. */
    private static final String EXAMPLE_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        Map<HttpStatus, Map<ErrorCode, ApiErrorSpec>> byStatus =
                new TreeMap<>(Comparator.comparingInt(HttpStatus::value));

        for (Class<? extends AppException> thrown : declaredOn(handlerMethod)) {
            record(byStatus, ApiErrors.of(thrown));
        }
        if (takesValidatedBody(handlerMethod)) {
            record(byStatus, VALIDATION);
        }
        if (!PublicEndpoints.isPublic(handlerMethod)) {
            // Read off the exception the entry point throws, not restated here: the
            // 401 documented and the 401 sent are then the same declaration.
            record(byStatus, ApiErrors.of(TokenInvalidException.class));
        }

        byStatus.forEach((status, specs) -> {
            String responseCode = String.valueOf(status.value());

            // A hand-written @ApiResponse wins: an endpoint that documents a status
            // itself has a reason, and overwriting it here would be silent.
            if (operation.getResponses().get(responseCode) == null) {
                operation.getResponses().addApiResponse(responseCode, problemResponse(specs.values()));
            }
        });

        return operation;
    }

    /** Method and controller both contribute, so a failure common to a class is stated once. */
    private static List<Class<? extends AppException>> declaredOn(HandlerMethod handlerMethod) {
        List<Class<? extends AppException>> thrown = new ArrayList<>();

        Throws onClass = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), Throws.class);
        Throws onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), Throws.class);

        if (onClass != null) {
            thrown.addAll(Arrays.asList(onClass.value()));
        }
        if (onMethod != null) {
            thrown.addAll(Arrays.asList(onMethod.value()));
        }
        return thrown;
    }

    private static boolean takesValidatedBody(HandlerMethod handlerMethod) {
        return Arrays.stream(handlerMethod.getMethodParameters())
                .anyMatch(ApiErrorResponsesCustomizer::isValidated);
    }

    private static boolean isValidated(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(Valid.class)
                || parameter.hasParameterAnnotation(Validated.class);
    }

    /** First declaration of a code wins; the same failure listed twice is one entry. */
    private static void record(Map<HttpStatus, Map<ErrorCode, ApiErrorSpec>> byStatus, ApiErrorSpec spec) {
        byStatus.computeIfAbsent(spec.status(), status -> new LinkedHashMap<>())
                .putIfAbsent(spec.code(), spec);
    }

    /**
     * Described inline rather than as a {@code $ref} to a shared response: OpenAPI drops
     * every sibling of a {@code $ref}, so a referenced response cannot carry a
     * description and the per-endpoint prose silently never reached the document. The
     * schema is still shared — that reference is inside the media type, where it is legal.
     */
    private static ApiResponse problemResponse(Iterable<ApiErrorSpec> specs) {
        StringBuilder description = new StringBuilder();
        MediaType problem = new MediaType().schema(new Schema<>().$ref(MugenApiDocs.PROBLEM_SCHEMA_REF));

        for (ApiErrorSpec spec : specs) {
            description.append("- `").append(spec.code().name()).append("` — ")
                    .append(spec.description()).append('\n');
            // Keyed by code, which is what Swagger UI labels the example picker with.
            problem.addExamples(spec.code().name(), exampleOf(spec));
        }

        return new ApiResponse()
                .description(description.toString())
                .content(new Content().addMediaType(MugenApiDocs.PROBLEM_MEDIA_TYPE, problem));
    }

    /**
     * The body this code actually produces, filled in from the same declaration. The
     * explanation doubles as {@code detail} because that is what {@code detail} is —
     * the human-readable half — and a made-up sentence would be a second wording of it.
     */
    private static Example exampleOf(ApiErrorSpec spec) {
        Map<String, Object> body = new LinkedHashMap<>();

        body.put("type", ApiErrors.typeUri(spec.code()).toString());
        body.put("title", spec.status().getReasonPhrase());
        body.put("status", spec.status().value());
        body.put("detail", spec.description());
        body.put("code", spec.code().name());
        body.put("traceId", EXAMPLE_TRACE_ID);

        // The one code that carries a field the others do not, and the reason a caller
        // reads this response differently from every other failure.
        if (spec.code() == ErrorCode.VALIDATION_FAILED) {
            body.put("detail", "Request validation failed.");
            body.put("errors", List.of(
                    Map.of("field", "email", "message", "must be a well-formed email address")));
        }

        return new Example().summary(spec.code().name()).value(body);
    }
}
