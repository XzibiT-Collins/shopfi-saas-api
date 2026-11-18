package com.example.perfume_budget.handlers;

import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.InputOutputException;
import com.example.perfume_budget.exception.handlers.GlobalExceptionHandler;
import com.example.perfume_budget.exception.handlers.JwtAuthenticationEntryPoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * SEC-01d (1): no error response carries an exception's inner text (a database message quoting row values, a JDBC
 * URL, a framework message echoing the request). Each case throws an exception whose message (and cause) carries
 * {@link #PII} through a real MVC dispatch into {@link GlobalExceptionHandler}; status codes are unchanged and the
 * {@code description} is the fixed text documented in docs/api/errors.md.
 */
@ExtendWith(OutputCaptureExtension.class)
class ErrorResponseRedactionTest {

    static final String EMAIL = "leak@x.test";
    static final String PII = "ERROR: duplicate key value violates unique constraint \"uc_users_email\"\n"
            + "  Detail: Key (email)=(" + EMAIL + ") already exists. jdbc:postgresql://db.internal:5432/shopfi?password=hunter2";

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @RestController
    static class Thrower {
        static volatile Callable<Exception> next;

        @GetMapping("/boom")
        String boom() throws Exception {
            throw next.call();
        }

        @SuppressWarnings("unused")
        void sample(String session) {
        }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Thrower()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private JsonNode answer(Exception thrown, int expectedStatus) throws Exception {
        Thrower.next = () -> thrown;
        MvcResult r = mvc.perform(get("/boom")).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(expectedStatus);
        assertNoInnerText(body);
        return json.readTree(body);
    }

    static void assertNoInnerText(String text) {
        assertThat(text).doesNotContain(EMAIL).doesNotContain("Key (email)").doesNotContain("duplicate key")
                .doesNotContain("uc_users_email").doesNotContain("jdbc:").doesNotContain("hunter2")
                .doesNotContain("Exception");
    }

    private static SQLException sql() {
        return new SQLException(PII, "23505");
    }

    // ---------------------------------------------------------------- the three the ticket names

    @Test
    void aDatabaseConstraintViolationIs409WithTheFixedText(CapturedOutput output) throws Exception {
        var cve = new org.hibernate.exception.ConstraintViolationException("could not execute statement [" + PII + "]",
                sql(), "insert into users (email) values (?)", "uc_users_email");
        JsonNode body = answer(new DataIntegrityViolationException("could not execute statement [" + PII + "]", cve), 409);
        assertThat(body.get("description").asText()).isEqualTo("A constraint violation occurred");
        // the WARN line names SQLState and constraint (safe), never the message / Detail
        assertThat(output.getAll()).contains("SQLState 23505, constraint uc_users_email").doesNotContain(EMAIL)
                .doesNotContain("Key (email)");
    }

    @Test
    void aTransactionFailureIs500WithTheFixedText() throws Exception {
        JsonNode body = answer(new TransactionSystemException("Could not commit JPA transaction",
                new IllegalStateException(PII, sql())), 500);
        assertThat(body.get("description").asText()).isEqualTo("Transaction failed");
    }

    @Test
    void anUnsupportedOperationIs405WithTheFixedText() throws Exception {
        JsonNode body = answer(new UnsupportedOperationException(PII), 405);
        assertThat(body.get("description").asText()).isEqualTo("Operation not supported");
    }

    // ---------------------------------------------------------------- every other handler that echoed a message

    @Test
    void aLibraryIllegalArgumentIs400WithTheFixedText() throws Exception {
        JsonNode body = answer(catchIt(() -> UUID.fromString(PII)), 400);
        assertThat(body.get("description").asText()).isEqualTo("Invalid request");
    }

    @Test
    void ourOwnIllegalArgumentKeepsItsBusinessMessage() throws Exception {
        JsonNode body = answer(new IllegalArgumentException("Invalid or expired token"), 400);
        assertThat(body.get("description").asText()).isEqualTo("Invalid or expired token");
    }

    @Test
    void anIllegalArgumentWithACauseIs400WithTheFixedText() throws Exception {
        JsonNode body = answer(new IllegalArgumentException(PII, sql()), 400);
        assertThat(body.get("description").asText()).isEqualTo("Invalid request");
    }

    @Test
    void anUnknownResourceIs404WithTheFixedText() throws Exception {
        JsonNode body = answer(new NoResourceFoundException(HttpMethod.GET, PII), 404);
        assertThat(body.get("description").asText()).isEqualTo("Resource not found");
    }

    @Test
    void anUnsupportedMethodIs405WithTheFixedText() throws Exception {
        JsonNode body = answer(new HttpRequestMethodNotSupportedException(PII), 405);
        assertThat(body.get("description").asText()).isEqualTo("Request method not supported");
    }

    @Test
    void aMissingCookieIs400NamingOnlyTheCookie() throws Exception {
        MethodParameter p = new MethodParameter(Thrower.class.getDeclaredMethod("sample", String.class), 0);
        JsonNode body = answer(new MissingRequestCookieException("refresh_token", p), 400);
        assertThat(body.get("description").asText()).isEqualTo("Missing required cookie: refresh_token.");
    }

    @Test
    void aMissingPartIs400NamingOnlyThePart() throws Exception {
        JsonNode body = answer(new MissingServletRequestPartException("file"), 400);
        assertThat(body.get("description").asText()).isEqualTo("Missing required part: file.");
    }

    @Test
    void anExpiredJwtIs401WithTheFixedText() throws Exception {
        JsonNode body = answer(new ExpiredJwtException(null, null, PII), 401);
        assertThat(body.get("description").asText()).isEqualTo("Token has expired");
    }

    @Test
    void anAuthorizationDenialIs403WithTheFixedText() throws Exception {
        JsonNode body = answer(new AuthorizationDeniedException(PII), 403);
        assertThat(body.get("description").asText()).isEqualTo("Access Denied");
    }

    @Test
    void anInputOutputFailureIs500WithTheFixedText() throws Exception {
        JsonNode body = answer(new InputOutputException(PII), 500);
        assertThat(body.get("description").asText()).isEqualTo("File processing failed");
    }

    @Test
    void aBindingFailureNamesTheFieldButNotTheConversionMessage() throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "request");
        result.addError(new FieldError("request", "id", PII, true, new String[]{"typeMismatch"}, null,
                "Failed to convert property value of type 'java.lang.String': " + PII));
        result.addError(new FieldError("request", "name", "", false, new String[]{"NotBlank"}, null, "Name is required"));
        JsonNode body = answer(new BindException(result), 400);
        assertThat(body.get("description").asText()).isEqualTo("Validation failed");
        assertThat(body.get("data").get("id").asText()).isEqualTo("Invalid value");
        assertThat(body.get("data").get("name").asText()).as("a validation message is user-facing").isEqualTo("Name is required");
    }

    @Test
    void anUnexpectedFailureIs500WithTheFixedText() throws Exception {
        JsonNode body = answer(new IllegalStateException(PII, sql()), 500);
        assertThat(body.get("description").asText()).isEqualTo("Unexpected error occurred");
    }

    @Test
    void aBusinessMessageIsKept() throws Exception {
        JsonNode body = answer(new BadRequestException("Cart is empty, add items and try again."), 400);
        assertThat(body.get("description").asText()).isEqualTo("Cart is empty, add items and try again.");
    }

    // ---------------------------------------------------------------- the security entry point (outside MVC advice)

    @Test
    void theAuthenticationEntryPointAnswers401WithTheFixedText() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new JwtAuthenticationEntryPoint(json).commence(new MockHttpServletRequest("GET", "/api/v1/x"), response,
                new InsufficientAuthenticationException(PII));
        assertThat(response.getStatus()).isEqualTo(401);
        assertNoInnerText(response.getContentAsString());
        assertThat(json.readTree(response.getContentAsString()).get("description").asText())
                .isEqualTo("Full authentication is required to access this resource");
    }

    private static Exception catchIt(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            return e;
        }
        throw new AssertionError("expected a failure");
    }
}
