package com.example.perfume_budget.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVT-01b L5 guard: no log statement in production code passes an e-mail address, phone number, OTP, token, password,
 * secret or username (an e-mail address here) as an argument. Logs name records by id or reference instead.
 *
 * <p>A source scan: comments are dropped and string literals blanked (the message text may say "e-mail"), then every
 * logging call is cut out (multi-line included) and its argument expressions must not mention such an identifier
 * ({@code user.getEmail()}, {@code normalizedEmail}, {@code otpCode}, {@code token}, ...). Upper-case constants
 * ({@code MIN_PASSWORD_LENGTH}) are not values and are allowed.
 *
 * <p>EVT-01c (4) closes the review's gaps:
 * <ul>
 *   <li><b>every logger form:</b> any receiver named like a logger ({@code log}, {@code LOG}, {@code logger},
 *       {@code LOGGER}, {@code auditLog}, ...), the SLF4J 2 fluent API ({@code log.atInfo().addArgument(..).log(..)}),
 *       {@code MDC.put} (every later line of the thread carries it) and {@code System.out/err};</li>
 *   <li><b>exception text:</b> {@code getMessage()} / {@code getLocalizedMessage()}, {@code String.valueOf(e)},
 *       {@code e.toString()}, {@code "..." + e}, and a throwable passed as an argument (its stack trace prints the
 *       message) at INFO and above: a database or transport message quotes values (Postgres
 *       {@code Key (email)=(..) already exists}, a rejected recipient). The class name is logged instead; the full
 *       throwable only at DEBUG / TRACE;</li>
 *   <li><b>indirect locals:</b> a variable assigned from a sensitive expression ({@code String who = user.getEmail();},
 *       {@code String m = e.getMessage();}) is itself sensitive, transitively;</li>
 *   <li><b>side effects (EVT-01c 9):</b> no {@code save(..)}, {@code delete(..)}, {@code publishEvent(..)}, ... inside
 *       log arguments: a log level that is switched off must not change what the code does.</li>
 *   <li><b>rework 1:</b> a logger with any name ({@code Logger audit = ..}), and a cause pulled out of a throwable
 *       ({@code e.getCause()}) counts as the throwable. The sanctioned way to put a trace into an ERROR line is
 *       {@code SafeStackTrace.of(e)} (class names and frames, never a message).</li>
 *   <li><b>SEC-01d (4) raw HTTP bodies:</b> at INFO and above, no raw response / request body: {@code getBody()},
 *       {@code .body()}, {@code getResponseBodyAsString()} / {@code getResponseBodyAsByteArray()} /
 *       {@code getResponseBodyAs(..)}, a variable named {@code body}, {@code responseBody}, {@code rawBody}, ... or a
 *       local assigned from one. A provider's error body can carry the customer's e-mail, authorization / access
 *       codes or card metadata. Reading ONE field of a parsed body ({@code response.getBody().message()}) is fine.</li>
 * </ul>
 */
class NoPersonalDataInLogsTest {

    private static final Pattern LOG_CALL = Pattern.compile(
            "\\b(?:\\w*[Ll]og(?:ger)?|\\w*LOG(?:GER)?)\\s*\\.\\s*(trace|debug|info|warn|error)\\s*\\(");
    private static final Pattern FLUENT_CALL = Pattern.compile("\\.\\s*at(Trace|Debug|Info|Warn|Error|Level)\\s*\\(");
    /** EVT-01c rework 1: a logger field or local with any name ({@code Logger audit = LoggerFactory.getLogger(..)}). */
    private static final Pattern LOGGER_DECLARATION = Pattern.compile("\\b(?:System\\s*\\.\\s*)?Logger\\s+(\\w+)\\s*[=;]");
    /** EVT-01c rework 1: a cause pulled out of a throwable is a throwable too ({@code log.error("..", e.getCause())}). */
    private static final String CAUSE_OF = "(?:\\s*\\.\\s*(?:getCause|getRootCause|getMostSpecificCause)\\s*\\(\\s*\\))+";
    private static final Pattern MDC_CALL = Pattern.compile("\\bMDC\\s*\\.\\s*(?:put|putCloseable)\\s*\\(");
    private static final Pattern STDOUT_CALL = Pattern.compile("\\bSystem\\s*\\.\\s*(?:out|err)\\s*\\.\\s*(?:print|println|printf|format)\\s*\\(");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)\\b\\w*(?:email|e_mail|phone|otp|token|password|passwd|secret|username)\\w*\\b");
    private static final Pattern CONSTANT = Pattern.compile("[A-Z0-9_]+");
    private static final Pattern EXCEPTION_TEXT = Pattern.compile("\\b(?:getMessage|getLocalizedMessage)\\s*\\(");
    private static final Pattern SIDE_EFFECT = Pattern.compile(
            "\\.\\s*(?:save|saveAll|saveAndFlush|delete\\w*|persist|merge|remove|executeUpdate|publishEvent|send|put|incrementAndGet|getAndIncrement)\\s*\\(");
    private static final Pattern CATCH_PARAM = Pattern.compile(
            "catch\\s*\\(\\s*(?:final\\s+)?[\\w.]+(?:\\s*\\|\\s*[\\w.]+)*\\s+(\\w+)\\s*\\)");
    private static final Pattern THROWABLE_PARAM = Pattern.compile("\\b\\w*(?:Exception|Error|Throwable)\\s+(\\w+)\\s*[,)=]");
    /** SEC-01d: a raw HTTP body ({@code getBody()}, {@code .body()}, {@code getResponseBodyAsString()}, ...). */
    private static final Pattern RAW_BODY_CALL = Pattern.compile(
            "\\.\\s*(?:getBody|body|getResponseBodyAsString|getResponseBodyAsByteArray|getResponseBodyAs)\\s*\\(");
    /** SEC-01d: a variable holding a body ({@code body}, {@code responseBody}, {@code rawBody}, {@code errorBody}). */
    private static final Pattern BODY_VARIABLE = Pattern.compile("(?i)\\b\\w*body\\b");
    /** What may follow a body and still be the whole body ({@code .toString()}, {@code .strip()}, ...). */
    private static final Pattern STILL_THE_BODY = Pattern.compile(
            "^\\s*\\.\\s*(?:toString|strip|trim|stripLeading|stripTrailing|toLowerCase|toUpperCase|substring|lines|getBytes)\\s*\\(");
    private static final Pattern ASSIGNMENT = Pattern.compile("\\b(\\w+)\\s*=(?!=)");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_]\\w*\\b");
    /** Wrappers whose argument IS the value: {@code String.valueOf(email)} is the e-mail address. */
    private static final Pattern PASS_THROUGH = Pattern.compile(
            "(?:String\\s*\\.\\s*(?:valueOf|format|join)|Objects\\s*\\.\\s*(?:toString|requireNonNull)|Optional\\s*\\.\\s*(?:of|ofNullable))\\s*\\(\\s*$");

    /** One logging call: its level ("info", "debug", "mdc", "stdout") and its argument text, literals blanked. */
    record Call(String level, String arguments) {}

    /** {@code source} with comments removed and the content of string / char / text-block literals blanked. */
    static String blankLiteralsAndComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                int j = source.indexOf('\n', i);
                i = j < 0 ? n : j;
            } else if (source.startsWith("/*", i)) {
                int j = source.indexOf("*/", i + 2);
                for (int k = i; k < (j < 0 ? n : j + 2); k++) {
                    out.append(source.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = j < 0 ? n : j + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int j = source.indexOf("\"\"\"", i + 3);
                out.append("\"\"");
                i = j < 0 ? n : j + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c) {
                    if (source.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                out.append(c).append(c);
                i = j + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String argumentsFrom(String code, int start) {
        int depth = 1;
        int j = start;
        while (depth > 0 && j < code.length()) {
            char c = code.charAt(j);
            if (c == '(') depth++;
            if (c == ')') depth--;
            j++;
        }
        return code.substring(start, Math.max(start, j - 1)).replaceAll("\\s+", " ");
    }

    /** Every logging call in {@code source} (any logger form, fluent API, MDC, stdout), string literals blanked. */
    static List<Call> logCalls(String source) {
        String code = blankLiteralsAndComments(source);
        List<Call> out = new ArrayList<>();
        Matcher m = LOG_CALL.matcher(code);
        while (m.find()) {
            out.add(new Call(m.group(1), argumentsFrom(code, m.end())));
        }
        Matcher decl = LOGGER_DECLARATION.matcher(code);
        Set<String> otherLoggers = new LinkedHashSet<>();
        while (decl.find()) {
            if (!Pattern.compile("(?:\\w*[Ll]og(?:ger)?|\\w*LOG(?:GER)?)").matcher(decl.group(1)).matches()) {
                otherLoggers.add(decl.group(1)); // not already caught by LOG_CALL
            }
        }
        for (String name : otherLoggers) {
            Matcher o = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\.\\s*(trace|debug|info|warn|error)\\s*\\(").matcher(code);
            while (o.find()) {
                out.add(new Call(o.group(1), argumentsFrom(code, o.end())));
            }
        }
        Matcher f = FLUENT_CALL.matcher(code);
        while (f.find()) {
            int end = code.indexOf(';', f.end());
            out.add(new Call(f.group(1).toLowerCase(), code.substring(f.end(), end < 0 ? code.length() : end).replaceAll("\\s+", " ")));
        }
        Matcher mdc = MDC_CALL.matcher(code);
        while (mdc.find()) {
            out.add(new Call("mdc", argumentsFrom(code, mdc.end())));
        }
        Matcher std = STDOUT_CALL.matcher(code);
        while (std.find()) {
            out.add(new Call("stdout", argumentsFrom(code, std.end())));
        }
        return out;
    }

    /** Kept for the EVT-01b self-test: just the argument texts. */
    static List<String> logArguments(String source) {
        return logCalls(source).stream().map(Call::arguments).toList();
    }

    static List<String> sensitiveIdentifiers(String arguments) {
        List<String> hits = new ArrayList<>();
        Matcher m = SENSITIVE.matcher(arguments);
        while (m.find()) {
            // a lookup BY a sensitive key (findByEmail(..), getActiveUserByEmail(..)) returns a record, not the key
            boolean lookup = m.group().contains("By") && arguments.substring(m.end()).stripLeading().startsWith("(");
            if (!CONSTANT.matcher(m.group()).matches() && !lookup) {
                hits.add(m.group());
            }
        }
        return hits;
    }

    /**
     * True if {@code expression} evaluates to sensitive data: a sensitive identifier or exception text at the top
     * level of the expression ({@code user.getEmail().strip()}, {@code "to " + email}) or inside a pass-through
     * wrapper ({@code String.valueOf(email)}), or a tainted variable. A sensitive name only inside some other call's
     * arguments ({@code repository.save(User.builder().email(email).build())}) does not make the result sensitive.
     */
    static boolean sensitiveValue(String expression, Set<String> tainted) {
        StringBuilder visible = new StringBuilder();
        int depth = 0;
        Deque passThrough = new Deque();
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                boolean wrapper = PASS_THROUGH.matcher(expression.substring(0, i + 1)).find();
                passThrough.push(wrapper);
                visible.append(depth == 0 ? c : ' ');
                depth += wrapper ? 0 : 1;
            } else if (c == ')') {
                boolean wrapper = passThrough.pop();
                depth -= wrapper ? 0 : 1;
                visible.append(depth == 0 ? c : ' ');
            } else {
                visible.append(depth == 0 ? c : ' ');
            }
        }
        String top = visible.toString();
        if (top.strip().endsWith(".build()")) {
            top = top.substring(0, top.lastIndexOf('.')).replaceAll("\\.\\w+\\(\\s*\\)", ""); // builder setters: an object
        }
        if (!sensitiveIdentifiers(top).isEmpty() || EXCEPTION_TEXT.matcher(expression).find() && EXCEPTION_TEXT.matcher(top).find()) {
            return true;
        }
        Matcher id = IDENTIFIER.matcher(top);
        while (id.find()) {
            if (tainted.contains(id.group())) {
                return true;
            }
        }
        return false;
    }

    /** A tiny boolean stack (no java.util.Deque of Boolean boxing noise in the scan loop). */
    private static final class Deque {
        private final java.util.ArrayDeque<Boolean> items = new java.util.ArrayDeque<>();

        void push(boolean b) {
            items.push(b);
        }

        boolean pop() {
            return !items.isEmpty() && items.pop();
        }
    }

    /** Local variables (any assignment in the file) that hold sensitive data, directly or through other locals. */
    static Set<String> taintedLocals(String code) {
        Set<String> tainted = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            Matcher a = ASSIGNMENT.matcher(code);
            while (a.find()) {
                String name = a.group(1);
                if (!tainted.contains(name) && sensitiveValue(rightHandSide(code, a.end()), tainted)) {
                    tainted.add(name);
                    changed = true;
                }
            }
        }
        return tainted;
    }

    /**
     * The expression assigned at {@code from}: up to the {@code ;} or {@code ,} that ends it, or the bracket that
     * closes the enclosing list ({@code @Column(name = "email") String x} assigns nothing beyond {@code ""}).
     */
    static String rightHandSide(String code, int from) {
        int depth = 0;
        int i = from;
        for (; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (--depth < 0) {
                    break;
                }
            } else if ((c == ';' || c == ',') && depth == 0) {
                break;
            }
        }
        return code.substring(from, i);
    }

    static Set<String> throwableNames(String code) {
        Set<String> names = new HashSet<>();
        for (Pattern p : List.of(CATCH_PARAM, THROWABLE_PARAM)) {
            Matcher m = p.matcher(code);
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /** Top-level comma-separated arguments of a call. */
    static List<String> splitArguments(String arguments) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            if (c == ')' || c == ']' || c == '}') depth--;
            if (c == ',' && depth == 0) {
                parts.add(arguments.substring(from, i).trim());
                from = i + 1;
            }
        }
        parts.add(arguments.substring(from).trim());
        return parts;
    }

    /** Index just past the call's closing parenthesis, given the index just past its opening one. */
    private static int closeOf(String text, int afterOpen) {
        int depth = 1;
        int j = afterOpen;
        while (depth > 0 && j < text.length()) {
            char c = text.charAt(j);
            if (c == '(') depth++;
            if (c == ')') depth--;
            j++;
        }
        return j;
    }

    /**
     * SEC-01d: true if {@code expression} evaluates to, or embeds, a whole raw HTTP body: a body call or body variable
     * that is not followed by a field accessor ({@code response.getBody().message()} reads one field and is fine;
     * {@code e.getResponseBodyAsString()}, {@code "x" + body}, {@code body.toString()} are the whole body).
     */
    static boolean rawBody(String fullExpression, Set<String> bodyLocals) {
        String expression = topLevel(fullExpression); // readValue(body, X.class) is not the body
        Matcher call = RAW_BODY_CALL.matcher(expression);
        while (call.find()) {
            if (wholeValue(expression, closeOf(expression, call.end()))) {
                return true;
            }
        }
        Matcher id = IDENTIFIER.matcher(expression);
        while (id.find()) {
            boolean declaredOrMember = id.start() > 0 && expression.charAt(id.start() - 1) == '.';
            boolean variable = BODY_VARIABLE.matcher(id.group()).matches() || bodyLocals.contains(id.group());
            String rest = expression.substring(id.end());
            if (variable && !declaredOrMember && !rest.stripLeading().startsWith("(") && wholeValue(expression, id.end())) {
                return true;
            }
        }
        return false;
    }

    /** {@code expression} with the arguments of every call blanked, except pass-through wrappers' arguments. */
    static String topLevel(String expression) {
        StringBuilder visible = new StringBuilder();
        int depth = 0;
        Deque passThrough = new Deque();
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                boolean wrapper = PASS_THROUGH.matcher(expression.substring(0, i + 1)).find();
                passThrough.push(wrapper);
                visible.append(depth == 0 ? c : ' ');
                depth += wrapper ? 0 : 1;
            } else if (c == ')') {
                boolean wrapper = passThrough.pop();
                depth -= wrapper ? 0 : 1;
                visible.append(depth == 0 ? c : ' ');
            } else {
                visible.append(depth == 0 ? c : ' ');
            }
        }
        return visible.toString();
    }

    private static boolean wholeValue(String expression, int end) {
        String rest = expression.substring(end);
        while (true) {
            Matcher m = STILL_THE_BODY.matcher(rest);
            if (!m.find()) {
                break;
            }
            rest = rest.substring(closeOf(rest, m.end()));
        }
        return !rest.stripLeading().startsWith(".");
    }

    /** SEC-01d: locals assigned a raw body ({@code String raw = e.getResponseBodyAsString();}), transitively. */
    static Set<String> rawBodyLocals(String code) {
        Set<String> locals = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            Matcher a = ASSIGNMENT.matcher(code);
            while (a.find()) {
                String name = a.group(1);
                if (!locals.contains(name) && rawBody(rightHandSide(code, a.end()), locals)) {
                    locals.add(name);
                    changed = true;
                }
            }
        }
        return locals;
    }

    /** Every reason {@code call} may write personal data or exception text, or has a side effect; empty if none. */
    static List<String> problems(Call call, Set<String> tainted, Set<String> throwables) {
        return problems(call, tainted, throwables, Set.of());
    }

    static List<String> problems(Call call, Set<String> tainted, Set<String> throwables, Set<String> bodyLocals) {
        Set<String> out = new LinkedHashSet<>(sensitiveIdentifiers(call.arguments()));
        if (EXCEPTION_TEXT.matcher(call.arguments()).find()) {
            out.add("exception message");
        }
        Matcher id = IDENTIFIER.matcher(call.arguments());
        while (id.find()) {
            if (tainted.contains(id.group())) {
                out.add("tainted local " + id.group());
            }
        }
        boolean detailAllowed = call.level().equals("debug") || call.level().equals("trace");
        for (String part : splitArguments(call.arguments())) {
            for (String t : throwables) {
                String q = Pattern.quote(t);
                boolean asText = part.matches("String\\s*\\.\\s*valueOf\\(\\s*" + q + "\\s*\\)|" + q + "\\s*\\.\\s*toString\\(\\s*\\)")
                        || Pattern.compile("(?:^|\\+)\\s*" + q + "\\s*(?:\\+|$)").matcher(part).find() && part.contains("+");
                boolean bare = part.equals(t) || part.matches(q + CAUSE_OF);
                if (asText || (bare && !detailAllowed)) {
                    out.add("throwable " + t);
                }
            }
        }
        if (SIDE_EFFECT.matcher(call.arguments()).find()) {
            out.add("side effect");
        }
        if (!detailAllowed) {
            for (String part : splitArguments(call.arguments())) {
                if (rawBody(part, bodyLocals)) {
                    out.add("raw http body");
                    break;
                }
            }
        }
        return new ArrayList<>(out);
    }

    static List<String> problemsIn(String source) {
        String code = blankLiteralsAndComments(source);
        Set<String> tainted = taintedLocals(code);
        Set<String> throwables = throwableNames(code);
        Set<String> bodyLocals = rawBodyLocals(code);
        List<String> out = new ArrayList<>();
        for (Call call : logCalls(source)) {
            List<String> p = problems(call, tainted, throwables, bodyLocals);
            if (!p.isEmpty()) {
                out.add(p + " in " + call.level() + "(" + call.arguments() + ")");
            }
        }
        return out;
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

    /** EVT-01c (4): each gap the EVT-01b review found is caught; the safe spellings next to them are not flagged. */
    @Test
    void theScannerCatchesEveryLoggerFormIndirectLocalsExceptionTextMdcAndSideEffects() {
        String sample = """
                class X {
                    void a(User user, Order order) {
                        String who = user.getEmail();
                        String again = who.strip();
                        log.info("Signed in {}", again);                                   // indirect local, transitive
                        User saved = userRepository.save(User.builder().email(who).build());
                        log.info("Saved user {}", saved.getId());                          // safe: id of an entity
                        LOGGER.warn("Hello {}", user.getPhoneNumber());                    // LOGGER
                        logger.error("Login of {}", username);                              // logger
                        auditLog.info("x {}", String.valueOf(who));                        // any *Log receiver
                        log.atInfo().addArgument(user.getEmail()).log("fluent {}");         // fluent API
                        MDC.put("user", user.getEmail());                                   // MDC
                        System.out.println("pw " + password);                               // stdout
                        log.info("Seeded {}", userRepository.save(order).getId());          // side effect
                        log.info("Order {} by {}", order.getId(), order.getUser().getId()); // safe
                        User found = userRepository.findByEmail(who).orElseThrow();
                        User built = User.builder().email(who).password(pw).build();
                        log.info("Found {} built {}", found.getId(), built.getId());      // safe: records
                    }
                    void b() {
                        try {
                            run();
                        } catch (IllegalStateException | IllegalArgumentException e) {
                            log.error("Failed: {}", e.getMessage());                        // exception message
                            String m = e.getLocalizedMessage();
                            log.warn("Failed: {}", m);                                      // ... through a local
                            log.error(String.valueOf(e));                                   // toString of it
                            log.warn("Failed " + e);                                        // concatenated
                            log.error("Failed", e);                                         // stack trace at ERROR
                            log.debug("Failed detail", e);                                  // allowed at DEBUG
                            log.error("Failed: {}", e.getClass().getName());                // safe
                            log.error("Failed: {}", SafeStackTrace.of(e));                  // safe: the sanctioned trace
                            log.error("Failed", e.getCause());                              // cause's trace at ERROR
                            log.debug("Failed cause", e.getCause());                        // allowed at DEBUG
                        }
                    }
                    private static final Logger audit = LoggerFactory.getLogger(X.class);
                    void d(User user) {
                        audit.info("Audit {}", user.getEmail());                            // any logger name
                        audit.info("Audit {}", user.getId());                               // safe
                    }
                    ResponseEntity<?> c(DataIntegrityViolationException ex) {
                        log.warn("Constraint: {}", ex.toString());                          // handler parameter
                        return null;
                    }
                }
                """;
        List<String> problems = problemsIn(sample);
        assertThat(problems).as(String.join("\n", problems)).hasSize(16);
        assertThat(problems).anySatisfy(p -> assertThat(p).isEqualTo("[getEmail] in info(\"\", user.getEmail())"));
        assertThat(problems).noneSatisfy(p -> assertThat(p).contains("SafeStackTrace"));
        assertThat(problems).noneSatisfy(p -> assertThat(p).contains("user.getId()"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("tainted local again"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("getPhoneNumber"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("username"));
        assertThat(problems).anySatisfy(p -> assertThat(p).startsWith("[tainted local who] in info"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("in mdc("));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("in stdout("));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("[getEmail] in info(")); // fluent
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("[side effect]"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("[tainted local m]"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("[throwable ex]"));
        assertThat(problems).filteredOn(p -> p.contains("throwable e]")).hasSize(4); // incl. e.getCause() at ERROR
        assertThat(problems).filteredOn(p -> p.contains("exception message")).hasSize(1);
        assertThat(problems).noneSatisfy(p -> assertThat(p).contains("saved.getId"));
        assertThat(problems).noneSatisfy(p -> assertThat(p).contains("found.getId"));
        assertThat(problems).noneSatisfy(p -> assertThat(p).contains("in debug("));
        assertThat(problems).noneSatisfy(p -> assertThat(p).contains("getClass"));
        assertThat(problems).noneSatisfy(p -> assertThat(p).contains("order.getUser().getId()"));
    }

    /** SEC-01d (4): raw HTTP bodies at INFO and above are flagged; one field of a parsed body and DEBUG are not. */
    @Test
    void theScannerFlagsRawHttpBodiesAtInfoAndAbove() {
        String sample = """
                class X {
                    void a(ResponseEntity<Foo> response, String orderNumber) {
                        try {
                            call();
                        } catch (HttpClientErrorException e) {
                            log.error("Paystack error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());   // 1
                            log.warn("Paystack error " + e.getResponseBodyAsString(StandardCharsets.UTF_8));      // 2
                            log.error("as {}", e.getResponseBodyAs(Map.class));                                     // 3
                            String raw = e.getResponseBodyAsString();
                            String trimmed = raw.strip();
                            log.error("Paystack said {}", trimmed);                                                 // 4: local
                            log.debug("Paystack body {}", e.getResponseBodyAsString());                           // DEBUG: ok
                            log.error("Status {}", e.getStatusCode().value());                                   // ok
                        }
                        log.info("Response: {}", response.getBody());                                            // 5
                        log.info("Response: {}", response.body().toString());                                    // 6
                        log.error("Paystack message {}", response.getBody().message());                          // ok: one field
                        String responseBody = fetch();
                        log.info("Got {}", responseBody);                                                        // 7: by name
                        log.info("Got {} bytes", responseBody.length());                                         // ok
                        log.info("Order {} failed", orderNumber);                                                // ok
                        log.info("Request body size {}", request.bodySize());                                    // ok
                        var outcome = settle("x" + orderNumber, () -> service.apply(orderNumber, body));
                        Foo parsed = MAPPER.readValue(body, Foo.class);
                        log.info("Settled {}: {} {}", orderNumber, outcome, parsed.id());                       // ok: built FROM a body
                    }
                }
                """;
        List<String> problems = problemsIn(sample);
        List<String> bodies = problems.stream().filter(p -> p.contains("raw http body")).toList();
        assertThat(bodies).as(String.join("\n", problems)).hasSize(7);
        assertThat(bodies).noneSatisfy(p -> assertThat(p).contains("in debug("));
        assertThat(bodies).noneSatisfy(p -> assertThat(p).contains("message()"));
        assertThat(bodies).noneSatisfy(p -> assertThat(p).contains("length()"));
        assertThat(bodies).noneSatisfy(p -> assertThat(p).contains("orderNumber"));
        assertThat(bodies).noneSatisfy(p -> assertThat(p).contains("bodySize"));
        assertThat(bodies).noneSatisfy(p -> assertThat(p).contains("getStatusCode().value()"));
    }

    @Test
    void noProductionLogStatementPassesPersonalDataExceptionTextOrHasASideEffect() throws IOException {
        Path root = Path.of("src/main/java");
        assertThat(root).isDirectory();
        List<String> offenders = new ArrayList<>();
        int calls = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                calls += logCalls(source).size();
                for (String problem : problemsIn(source)) {
                    offenders.add(root.relativize(file) + ": " + problem);
                }
            }
        }
        assertThat(calls).as("the scan saw the code base's log statements").isGreaterThan(150);
        assertThat(offenders).isEmpty();
    }
}
