package com.mugen.web.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mugen.shared.error.ErrorCode;
import com.mugen.shared.trace.TraceIdHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The response body shape is a contract every client parses, so it is asserted
 * directly rather than assumed.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        TraceIdHolder.clear();
    }

    @Test
    @DisplayName("an AppException renders as problem+json with its own status and code")
    void rendersAppException() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Email taken."))
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_ALREADY_REGISTERED.name()))
                .andExpect(jsonPath("$.type").value("https://mugen.dev/errors/email-already-registered"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("an unexpected exception is a 500 that withholds the real message")
    void hidesUnexpectedExceptionDetail() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR.name()))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                // The raw message names an internal table — it must not reach the
                // client, only the log.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("dbo.secret_internal_table"))));
    }

    @Test
    @DisplayName("validation failures list every offending field in errors[]")
    void listsValidationErrors() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "username": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("the traceId in the body is the one already in scope, not a new one")
    void reusesActiveTraceId() throws Exception {
        String active = "4bf92f3577b34da6a3ce929d0e0e4736";
        TraceIdHolder.set(active);

        mockMvc.perform(get("/test/conflict"))
                .andExpect(jsonPath("$.traceId").value(active));
    }

    /**
     * A traceId nothing ever logged is decoration: it is the handle a user quotes
     * to support, and it has to lead somewhere. These two paths answered with one
     * and logged nothing at all until {@code handleExceptionInternal} was added.
     */
    @Test
    @DisplayName("every error response leaves a log line behind, including the 4xx Spring handles itself")
    void everyErrorResponseIsLogged() throws Exception {
        ListAppender<ILoggingEvent> logged = captureHandlerLogs();

        // Validation — handled by our own override.
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "username": ""}
                                """))
                .andExpect(status().isBadRequest());

        // Malformed body — handled entirely inside ResponseEntityExceptionHandler.
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest());

        assertThat(logged.list)
                .describedAs("both 4xx responses must be logged")
                .hasSize(2)
                .allMatch(event -> event.getLevel() == Level.WARN);
    }

    /**
     * Spring assembles {@code HttpMessageNotReadableException}'s message out of the
     * body it could not parse, so logging it verbatim would copy a registration
     * payload — password included — straight into Loki.
     */
    @Test
    @DisplayName("a malformed body is logged by exception type, never by echoing the body")
    void malformedBodyIsNotEchoedIntoTheLog() throws Exception {
        ListAppender<ILoggingEvent> logged = captureHandlerLogs();

        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"hunter2\", oops}"))
                .andExpect(status().isBadRequest());

        assertThat(logged.list).hasSize(1);
        assertThat(logged.list.getFirst().getFormattedMessage())
                .contains("HttpMessageNotReadableException")
                .doesNotContain("hunter2")
                .doesNotContain("password");
    }

    private static ListAppender<ILoggingEvent> captureHandlerLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    // ---------------------------------------------------------------------

    record RegisterRequest(@Email @NotBlank String email, @NotBlank String username) {
    }

    @RestController
    static class TestController {

        @org.springframework.web.bind.annotation.GetMapping("/test/conflict")
        void conflict() {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email taken.");
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("connection to dbo.secret_internal_table failed");
        }

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody RegisterRequest request) {
            // Never reached — validation rejects the payload first.
        }
    }
}
