package com.example.perfume_budget.utils;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SEC-01d: the SAFE facts of a database failure, for log lines: the SQLState and the name of the violated constraint.
 * Never the driver / server message and never its {@code Detail} (Postgres {@code Key (email)=(..) already exists}
 * quotes the row's values). Both facts are checked against a strict character set before they are printed, so a
 * value can never ride along in them.
 *
 * <p>Sources, per throwable: {@link SQLException#getSQLState()}, Hibernate's
 * {@code org.hibernate.exception.ConstraintViolationException#getConstraintName()} and the structured constraint
 * field of the Postgres server error ({@code PSQLException.getServerErrorMessage().getConstraint()}, read
 * reflectively: the driver is a runtime-only dependency).
 */
public final class DbErrorDetails {

    private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$.]{0,127}");
    private static final int MAX_DEPTH = 32;

    private final String sqlState;
    private final String constraint;

    private DbErrorDetails(String sqlState, String constraint) {
        this.sqlState = sqlState;
        this.constraint = constraint;
    }

    /** The first SQLState and the first constraint name found along the cause chain of {@code throwable}. */
    public static DbErrorDetails of(Throwable throwable) {
        String state = null;
        String name = null;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = throwable; t != null && seen.add(t) && depth++ < MAX_DEPTH; t = t.getCause()) {
            if (state == null) {
                state = sqlStateOf(t);
            }
            if (name == null) {
                name = constraintOf(t);
            }
        }
        return new DbErrorDetails(state, name);
    }

    /** The safe facts of this one throwable only, as {@code " [SQLState 23505, constraint uc_users_email]"}; "" if none. */
    static String annotationOf(Throwable t) {
        String state = sqlStateOf(t);
        String name = constraintOf(t);
        if (state == null && name == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(" [");
        if (state != null) {
            out.append("SQLState ").append(state);
        }
        if (name != null) {
            out.append(state != null ? ", " : "").append("constraint ").append(name);
        }
        return out.append(']').toString();
    }

    public String sqlState() {
        return sqlState;
    }

    public String constraint() {
        return constraint;
    }

    /** {@code "SQLState 23505, constraint uc_users_email"}; a missing fact reads {@code none}. */
    @Override
    public String toString() {
        return "SQLState " + (sqlState != null ? sqlState : "none") + ", constraint " + (constraint != null ? constraint : "none");
    }

    private static String sqlStateOf(Throwable t) {
        String state = null;
        if (t instanceof SQLException sql) {
            state = sql.getSQLState();
        } else if (t instanceof org.hibernate.JDBCException jdbc) {
            state = jdbc.getSQLState();
        }
        return state != null && SQL_STATE.matcher(state).matches() ? state : null;
    }

    private static String constraintOf(Throwable t) {
        String name = null;
        if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
            name = cve.getConstraintName();
        } else if (t instanceof SQLException sql) {
            name = postgresConstraint(sql);
        }
        return name != null && IDENTIFIER.matcher(name).matches() ? name : null;
    }

    private static String postgresConstraint(SQLException e) {
        try {
            Method serverError = e.getClass().getMethod("getServerErrorMessage");
            Object message = serverError.invoke(e);
            if (message == null) {
                return null;
            }
            Object constraint = message.getClass().getMethod("getConstraint").invoke(message);
            return constraint instanceof String s ? s : null;
        } catch (ReflectiveOperationException | RuntimeException notPostgres) {
            return null;
        }
    }
}
