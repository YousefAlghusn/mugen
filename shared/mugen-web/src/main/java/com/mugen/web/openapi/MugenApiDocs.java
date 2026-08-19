package com.mugen.web.openapi;

/**
 * Names shared between the OpenAPI document a service builds and the customizers
 * that fill it in. Constants rather than literals so a rename cannot leave the two
 * halves referring to different things.
 */
public final class MugenApiDocs {

    /** The scheme behind Swagger UI's "Authorize" button. */
    public static final String BEARER_SCHEME = "bearerAuth";

    /**
     * The RFC 9457 body every failure has. Referenced from inside a response's media
     * type by {@code ApiErrorResponsesCustomizer} — never as the response itself, since
     * OpenAPI drops a {@code $ref}'s siblings and the description would go with them.
     */
    public static final String PROBLEM_SCHEMA = "Problem";

    public static final String PROBLEM_SCHEMA_REF = "#/components/schemas/" + PROBLEM_SCHEMA;

    public static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    private MugenApiDocs() {
    }
}
