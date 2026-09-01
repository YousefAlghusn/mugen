package com.mugen.auth.unit;

import com.mugen.auth.dto.RegisterRequest;
import com.mugen.test.UnitTest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@UnitTest
@DisplayName("RegisterRequest bounds the password by UTF-8 bytes, matching BCrypt's real limit")
class RegisterRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private long passwordViolations(String password) {
        return validator.validate(new RegisterRequest("username", "user@mugen.dev", password))
                .stream()
                .filter(violation -> violation.getPropertyPath().toString().equals("password"))
                .count();
    }

    @Test
    @DisplayName("72 ASCII bytes are accepted, 73 are not")
    void boundsAsciiAtSeventyTwoBytes() {
        assertThat(passwordViolations("a".repeat(72))).isZero();
        assertThat(passwordViolations("a".repeat(73))).isEqualTo(1);
    }

    @Test
    @DisplayName("a multi-byte password over 72 bytes is rejected though it is under 72 characters")
    void countsBytesNotCharactersForMultiByte() {
        // 40 two-byte characters: 40 chars, 80 bytes. A char-count cap would pass it,
        // and BCrypt would then 500 or silently truncate it.
        String multiByte = "é".repeat(40);
        assertThat(multiByte.length()).isLessThan(72);
        assertThat(passwordViolations(multiByte)).isEqualTo(1);
    }

    @Test
    @DisplayName("the 8-character lower bound still holds")
    void keepsTheLowerBound() {
        assertThat(passwordViolations("short")).isEqualTo(1);
    }
}
