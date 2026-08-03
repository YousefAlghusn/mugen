package com.mugen.web.openapi;

import com.mugen.shared.error.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;

import java.util.Arrays;
import java.util.List;

/**
 * Declares the one RFC 9457 shape every service answers failures with, so an
 * operation can reference it instead of restating the schema.
 * <p>
 * Described by hand rather than from {@code ProblemDetail.class}, because the three
 * fields a client most needs — {@code code}, {@code traceId}, {@code errors} — are
 * set as dynamic properties by {@code GlobalExceptionHandler} and do not appear on
 * the class at all. Generating from the type documented a response nobody sends.
 */
public class ProblemResponseCustomizer implements GlobalOpenApiCustomizer {

    @Override
    public void customise(OpenAPI openApi) {
        Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();

        components.addSchemas(MugenApiDocs.PROBLEM_SCHEMA, problemSchema());
        components.addResponses(MugenApiDocs.PROBLEM_RESPONSE, new ApiResponse()
                .description("RFC 9457 problem document")
                .content(new Content().addMediaType(
                        MugenApiDocs.PROBLEM_MEDIA_TYPE,
                        new MediaType().schema(new Schema<>().$ref(MugenApiDocs.PROBLEM_SCHEMA_REF)))));

        openApi.setComponents(components);
    }

    private static Schema<?> problemSchema() {
        return new ObjectSchema()
                .description("Every failure from every mugen service has this shape.")
                .addProperty("type", new StringSchema().format("uri")
                        .description("Stable identifier for the error kind"))
                .addProperty("title", new StringSchema().description("The status' reason phrase"))
                .addProperty("status", new IntegerSchema().format("int32"))
                .addProperty("detail", new StringSchema().description("Human-readable, safe to show a user"))
                .addProperty("instance", new StringSchema().format("uri").description("The path that failed"))
                .addProperty("code", codeSchema())
                .addProperty("traceId", new StringSchema()
                        .description("Quote this when reporting a problem — it is what ties this response "
                                + "to the server-side log line"))
                .addProperty("errors", validationErrors())
                .required(List.of("status", "code", "traceId"));
    }

    /** Enumerated from {@link ErrorCode} itself, so a new code documents itself. */
    private static Schema<?> codeSchema() {
        StringSchema code = new StringSchema();
        code.setDescription("Machine-readable error, for branching in a client");
        Arrays.stream(ErrorCode.values()).map(Enum::name).forEach(code::addEnumItem);
        return code;
    }

    private static Schema<?> validationErrors() {
        return new ArraySchema()
                .description("Present only on a validation failure")
                .items(new ObjectSchema()
                        .addProperty("field", new StringSchema())
                        .addProperty("message", new StringSchema()));
    }
}
