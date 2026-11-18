package com.example.perfume_budget.utils;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** SEC-01d: SQLState and constraint name of a database failure, never its message or Detail. */
class DbErrorDetailsTest {

    static final String EMAIL = "leak@x.test";

    /** What the Postgres driver throws for a unique violation on users.email (structured server fields). */
    static PSQLException uniqueViolationOnEmail() {
        return new PSQLException(new ServerErrorMessage("SERROR\0VERROR\0C23505\0"
                + "Mduplicate key value violates unique constraint \"uc_users_email\"\0"
                + "DKey (email)=(" + EMAIL + ") already exists.\0stenant_acme\0tusers\0nuc_users_email\0"));
    }

    @Test
    void thePostgresServerErrorGivesSqlStateAndConstraint_notTheDetail() {
        DbErrorDetails d = DbErrorDetails.of(new IllegalStateException("wrapped " + EMAIL, uniqueViolationOnEmail()));
        assertThat(d.sqlState()).isEqualTo("23505");
        assertThat(d.constraint()).isEqualTo("uc_users_email");
        assertThat(d).hasToString("SQLState 23505, constraint uc_users_email");
        assertThat(d.toString()).doesNotContain(EMAIL);
    }

    @Test
    void hibernatesConstraintViolationGivesItsConstraintName() {
        SQLException sql = new SQLException("Key (email)=(" + EMAIL + ")", "23505");
        var cve = new org.hibernate.exception.ConstraintViolationException("could not execute statement " + EMAIL, sql,
                "insert into users", "uc_users_email");
        DbErrorDetails d = DbErrorDetails.of(new org.springframework.dao.DataIntegrityViolationException("x " + EMAIL, cve));
        assertThat(d).hasToString("SQLState 23505, constraint uc_users_email");
    }

    @Test
    void aValueThatIsNotAnIdentifierOrSqlStateIsNeverPrinted() {
        SQLException sql = new SQLException("m", "Key (email)=(" + EMAIL + ")");
        var cve = new org.hibernate.exception.ConstraintViolationException("m", sql, "sql", "Key (email)=(" + EMAIL + ")");
        DbErrorDetails d = DbErrorDetails.of(cve);
        assertThat(d).hasToString("SQLState none, constraint none");
        assertThat(DbErrorDetails.annotationOf(cve)).isEmpty();
    }

    @Test
    void aNonDatabaseFailureHasNoFacts() {
        assertThat(DbErrorDetails.of(new IllegalStateException(EMAIL))).hasToString("SQLState none, constraint none");
        assertThat(DbErrorDetails.of(null)).hasToString("SQLState none, constraint none");
    }

    @Test
    void theStackTraceLineOfTheDriverExceptionCarriesBoth() {
        String trace = SafeStackTrace.of(new RuntimeException(EMAIL, uniqueViolationOnEmail())).toString();
        assertThat(trace).contains("Caused by: org.postgresql.util.PSQLException [SQLState 23505, constraint uc_users_email]\n");
        assertThat(trace).doesNotContain(EMAIL).doesNotContain("Key (email)").doesNotContain("duplicate key");
    }
}
