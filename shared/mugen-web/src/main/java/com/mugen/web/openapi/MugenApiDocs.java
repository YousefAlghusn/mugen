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
     * Reusable {@code components.responses} entry for an RFC 9457 failure. Referenced
     * from an operation as {@code @ApiResponse(responseCode = "409", ref = PROBLEM_REF)},
     * which is the whole point — one line instead of a nested @Content and @Schema.
     */
    public static final String PROBLEM_RESPONSE = "Problem";

    public static final String PROBLEM_REF = "#/components/responses/" + PROBLEM_RESPONSE;

    public static final String PROBLEM_SCHEMA = "Problem";

    public static final String PROBLEM_SCHEMA_REF = "#/components/schemas/" + PROBLEM_SCHEMA;

    public static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    private MugenApiDocs() {
    }
}
