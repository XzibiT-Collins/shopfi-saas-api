package com.example.perfume_budget.platform.billing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures every log event of the JVM (our code at ALL levels, frameworks at their configured levels) so a test can prove that a secret never reached a log line,
 * a message, an MDC value or a stack trace. Use in try-with-resources.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger app = (Logger) LoggerFactory.getLogger("com.example.perfume_budget");
    private final Level previous;

    public LogCapture() {
        // OUR code at every level (a debug line that leaks is a leak); the frameworks at the configured levels (a framework's
        // TRACE dump of a request is never on in production)
        previous = app.getLevel();
        app.setLevel(Level.ALL);
        appender.start();
        root.addAppender(appender);
    }

    /** Every captured event rendered as one text: formatted message, arguments, MDC, and the throwable chain with messages. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : new ArrayList<>(appender.list)) {
            sb.append(e.getLoggerName()).append(' ').append(e.getLevel()).append(' ').append(e.getFormattedMessage()).append('\n');
            if (e.getArgumentArray() != null) {
                for (Object a : e.getArgumentArray()) {
                    sb.append("  arg ").append(a).append('\n');
                }
            }
            sb.append("  mdc ").append(e.getMDCPropertyMap()).append('\n');
            for (IThrowableProxy t = e.getThrowableProxy(); t != null; t = t.getCause()) {
                sb.append("  throwable ").append(t.getClassName()).append(": ").append(t.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    public List<ILoggingEvent> events() {
        return new ArrayList<>(appender.list);
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        app.setLevel(previous);
        appender.stop();
    }
}
