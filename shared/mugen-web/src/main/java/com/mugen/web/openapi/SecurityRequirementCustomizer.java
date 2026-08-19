package com.mugen.web.openapi;

import com.mugen.web.security.PublicEndpoint;
import com.mugen.web.security.PublicEndpoints;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.web.method.HandlerMethod;

/**
 * Marks every operation whose handler is not {@link PublicEndpoint} as needing a bearer
 * token.
 * <p>
 * Reads the same annotation the filter chain is built from, so the document cannot
 * claim an endpoint is open while the chain refuses it — a failure that is otherwise
 * silent, since only the docs lie. A global security requirement would be the easy
 * alternative and is wrong: it marks {@code /login} as needing the token it issues.
 * <p>
 * The 401 that goes with the requirement is written by
 * {@link ApiErrorResponsesCustomizer}, which owns every error response so that a
 * secured endpoint also declaring its own 401 gets one merged response, not two.
 */
public class SecurityRequirementCustomizer implements GlobalOperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (PublicEndpoints.isPublic(handlerMethod)) {
            return operation;
        }

        operation.addSecurityItem(new SecurityRequirement().addList(MugenApiDocs.BEARER_SCHEME));

        return operation;
    }
}
