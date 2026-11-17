package com.example.perfume_budget.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVT-01b L5 guard: no log statement in production code passes an e-mail address, phone number, OTP, token, password,
 * secret or username (an e-mail address here) as an argument. Logs name records by id or reference instead.
 *
 * <p>A source scan: every {@code log.<level>(...)} call (multi-line included) is cut out, its string literals are
 * blanked (the message text may say "e-mail"), and the remaining argument expressions must not mention such an
 * identifier ({@code user.getEmail()}, {@code normalizedEmail}, {@code otpCode}, {@code token}, ...). Upper-case
 * constants ({@code MIN_PASSWORD_LENGTH}) are not values and are allowed.
 */
class NoPersonalDataInLogsTest {

    private static final Pattern LOG_CALL = Pattern.compile("\\b(?:log|logger)\\.(?:trace|debug|info|warn|error)\\s*\\(");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)\\b\\w*(?:email|e_mail|phone|otp|token|password|passwd|secret|username)\\w*\\b");
    private static final Pattern CONSTANT = Pattern.compile("[A-Z0-9_]+");

    /** The argument list of every log call in {@code source}, string literals blanked. */
    static List<String> logArguments(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = LOG_CALL.matcher(source);
        while (m.find()) {
            int depth = 1;
            int j = m.end();
            boolean inString = false;
            boolean inChar = false;
            StringBuilder args = new StringBuilder();
            while (depth > 0 && j < source.length()) {
                char c = source.charAt(j);
                if (inString || inChar) {
                    if (c == '\\') {
                        j++;
                    } else if ((inString && c == '"') || (inChar && c == '\'')) {
                        inString = false;
                        inChar = false;
                        args.append(c);
                    }
                } else if (c == '"') {
                    inString = true;
                    args.append(c);
                } else if (c == '\'') {
                    inChar = true;
                    args.append(c);
                } else {
                    if (c == '(') depth++;
                    if (c == ')') depth--;
                    if (depth > 0) args.append(c);
                }
                j++;
            }
            out.add(args.toString().replaceAll("\\s+", " "));
        }
        return out;
    }

    static List<String> sensitiveIdentifiers(String arguments) {
        List<String> hits = new ArrayList<>();
        Matcher m = SENSITIVE.matcher(arguments);
        while (m.find()) {
            if (!CONSTANT.matcher(m.group()).matches()) {
                hits.add(m.group());
            }
        }
        return hits;
    }

    @Test
    void theScannerFindsSensitiveArgumentsAndIgnoresMessageText() {
        String sample = """
                log.info("User {} verified", user.getEmail());
                log.warn("Sending the OTP e-mail with token {}",
                        otpCode);
                log.info("Order {} paid (e-mail, password and token not logged)", order.getId());
                log.warn("Password must be {} long", MIN_PASSWORD_LENGTH);
                log.error("Reset for {}", resetPasswordToken, e);
                """;
        List<String> args = logArguments(sample);
        assertThat(args).hasSize(5);
        assertThat(sensitiveIdentifiers(args.get(0))).containsExactly("getEmail");
        assertThat(sensitiveIdentifiers(args.get(1))).containsExactly("otpCode");
        assertThat(sensitiveIdentifiers(args.get(2))).isEmpty();
        assertThat(sensitiveIdentifiers(args.get(3))).isEmpty();
        assertThat(sensitiveIdentifiers(args.get(4))).containsExactly("resetPasswordToken");
    }

    @Test
    void noProductionLogStatementPassesAnEmailPhoneOtpTokenOrPassword() throws IOException {
        Path root = Path.of("src/main/java");
        assertThat(root).isDirectory();
        List<String> offenders = new ArrayList<>();
        int calls = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String args : logArguments(source)) {
                    calls++;
                    List<String> hits = sensitiveIdentifiers(args);
                    if (!hits.isEmpty()) {
                        offenders.add(root.relativize(file) + ": " + hits + " in log(" + args + ")");
                    }
                }
            }
        }
        assertThat(calls).as("the scan saw the code base's log statements").isGreaterThan(150);
        assertThat(offenders).isEmpty();
    }
}
