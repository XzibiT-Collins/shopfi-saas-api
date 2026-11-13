package com.example.perfume_budget.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.filter.JWTUtil;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** TENANT-10 AC3: validating a JWT must never write the token (or its parts) to any log level. */
class JwtUtilLoggingTest {

    @Test
    void validateTokenNeverLogsTheToken() {
        JWTUtil util = new JWTUtil();
        ReflectionTestUtils.setField(util, "jwtSecret",
                "dGVzdC1zZWNyZXQta2V5LXRlc3Qtc2VjcmV0LWtleS10ZXN0LXNlY3JldC1rZXk=");
        ReflectionTestUtils.setField(util, "jwtAccessExpirationMs", 60_000L);
        ReflectionTestUtils.setField(util, "jwtRefreshExpirationMs", 120_000L);
        String token = util.generateRefreshToken("a@b.c", "ROLE_USER", 1L, "A B");
        String payload = token.split("\\.")[1];

        Logger logger = (Logger) LoggerFactory.getLogger(JWTUtil.class);
        Level old = logger.getLevel();
        logger.setLevel(Level.TRACE);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            util.validateToken(token);
            try { util.validateToken(token + "x"); } catch (RuntimeException ignored) { }
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(old);
        }
        assertThat(appender.list).isNotEmpty();
        for (ILoggingEvent e : appender.list) {
            assertThat(e.getFormattedMessage()).doesNotContain(token).doesNotContain(payload);
        }
    }
}
