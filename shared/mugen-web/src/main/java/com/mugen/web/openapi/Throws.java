package com.mugen.web.openapi;

import com.mugen.web.error.AppException;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The failures this endpoint can answer with, named by their exception class.
 * <p>
 * Status, code and explanation all come from each class's {@code @ApiError}, so an
 * endpoint says only <em>which</em> failures it produces and never repeats what they
 * mean — the alternative had four endpoints describing the same 401 four ways. On a
 * controller it applies to every handler in it.
 * <p>
 * Referencing the class rather than the code is what makes this compiler-checked: an
 * exception that is deleted or renamed takes the documentation with it.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Throws {

    Class<? extends AppException>[] value();
}
