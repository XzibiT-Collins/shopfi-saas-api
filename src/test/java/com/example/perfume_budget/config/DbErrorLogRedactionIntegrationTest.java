package com.example.perfume_budget.config;

import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.exception.handlers.GlobalExceptionHandler;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * SEC-01d (2): a real unique violation on users.email against Postgres. Hibernate's SqlExceptionHelper (which would
 * log {@code Detail: Key (email)=(..) already exists} at ERROR) is OFF through the base properties; our own WARN line
 * names the SQLState and the constraint, and no captured log line at any level carries the e-mail address. The 409
 * body is the fixed text. Also (1): Spring Boot's /error body never carries the exception message.
 */
@ExtendWith(OutputCaptureExtension.class)
class DbErrorLogRedactionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired TestTenants tenants;
    @Autowired UserRepository userRepository;
    @Autowired GlobalExceptionHandler handler;
    @Autowired BasicErrorController errorController;
    @Autowired Environment environment;
    @Autowired ObjectMapper objectMapper;

    @Test
    void aUniqueViolationOnEmailLogsSqlStateAndConstraint_andNoLineCarriesTheEmail(CapturedOutput output) throws Exception {
        assertThat(environment.getProperty("logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper")).isEqualTo("OFF");
        TestTenant acme = tenants.acme();
        String email = acme.adminEmail();
        int before = output.getAll().length();

        DataIntegrityViolationException ex = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> tenants.runAs(acme, () -> userRepository.saveAndFlush(User.builder().fullName("Dup")
                        .email(email).password("x").roles(UserRole.CUSTOMER).authProvider(AuthProvider.LOCAL)
                        .isActive(true).emailVerified(true).build())));
        assertThat(ex).as("Postgres rejected the duplicate e-mail").isNotNull();
        assertThat(ex.getMostSpecificCause().getMessage()).as("the driver's text does quote it").contains(email);

        ResponseEntity<?> answer = handler.handleDataIntegrityViolation(ex);
        String body = objectMapper.writeValueAsString(answer.getBody());
        assertThat(answer.getStatusCode().value()).isEqualTo(409);
        assertThat(body).doesNotContain(email).doesNotContain("Key (email)").contains("A constraint violation occurred");

        String logged = output.getAll().substring(before);
        assertThat(logged.lines().filter(l -> l.contains(email))).as("no log line at any level").isEmpty();
        assertThat(logged).doesNotContain("Key (email)").doesNotContain("duplicate key").doesNotContain("SqlExceptionHelper");
        Matcher ours = Pattern.compile("WARN .*Constraint violation answered 409: SQLState 23505, constraint (uc_users_email|idx_email)")
                .matcher(logged);
        assertThat(ours.find()).as("our WARN line names the SQLState and the constraint:\n" + logged).isTrue();
    }

    @Test
    void springBootsErrorBodyNeverCarriesTheExceptionMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setParameter("message", "true");
        request.setParameter("trace", "true");
        request.setParameter("errors", "true");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/x");
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "Key (email)=(boot-leak@x.test) already exists");
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION,
                new IllegalStateException("jdbc:postgresql://db.internal/shopfi Key (email)=(boot-leak@x.test)"));
        ResponseEntity<Map<String, Object>> r = errorController.error(request);
        assertThat(r.getStatusCode().value()).isEqualTo(500);
        assertThat(r.getBody()).doesNotContainKeys("message", "trace", "exception", "errors");
        assertThat(String.valueOf(r.getBody())).doesNotContain("boot-leak").doesNotContain("jdbc:").doesNotContain("Key (email)");
    }
}
