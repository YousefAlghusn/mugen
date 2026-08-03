package com.mugen.web.openapi;

import com.mugen.web.security.PublicEndpoint;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * Marks every operation whose handler is not {@link PublicEndpoint} as needing a
 * bearer token, and gives it the matching 401.
 * <p>
 * Derived from the same annotation the filter chain is built from, so the document
 * cannot claim an endpoint is open while the filter chain refuses it. Written by hand
 * this failure is silent — the endpoint keeps working and only the docs lie, which no
 * amount of exercising the API reveals.
 * <p>
 * A global security requirement would have been the easy alternative and is wrong: it
 * marks {@code /login} and {@code /register} as requiring the token they exist to
 * issue.
 */
public class SecurityRequirementCustomizer implements GlobalOperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (isPublic(handlerMethod)) {
            return operation;
        }

        operation.addSecurityItem(new SecurityRequirement().addList(MugenApiDocs.BEARER_SCHEME));

        // Every secured endpoint answers this identically, so stating it per method
        // was pure repetition.
        operation.getResponses().addApiResponse("401", new ApiResponse()
                .description("Missing, expired or invalid access token")
                .$ref(MugenApiDocs.PROBLEM_REF));

        return operation;
    }

    private static boolean isPublic(HandlerMethod handlerMethod) {
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), PublicEndpoint.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), PublicEndpoint.class);
    }
}
