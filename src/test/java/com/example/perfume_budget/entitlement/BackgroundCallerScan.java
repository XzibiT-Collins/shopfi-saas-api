package com.example.perfume_budget.entitlement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BILL-01c AC6: a source-level scan of the background entry points of the application and of what they reach.
 *
 * <p>Entry points: every method annotated {@code @Scheduled}, {@code @Async}, {@code @EventListener} or
 * {@code @TransactionalEventListener}, and every method of a class that schedules work programmatically
 * ({@code ScheduledExecutorService}, {@code TaskScheduler}). Each must be classified with {@code @NotPlanGated(reason)} or
 * {@code @PlanGatedBackground(reason)}, on the method or on its class. The call graph (field-typed receivers, interfaces
 * resolved to their implementations, same-class calls, method references) is followed from every entry point to the
 * methods that call {@code entitlementService.requireRoute / requireRead / requireWrite / requireLimitSlot /
 * precheckLimitSlot}; a {@code @NotPlanGated} entry that reaches one is a finding (outside HTTP those checks fail closed).
 * The graph is an over-approximation (it matches by method name), which is the safe direction.
 */
final class BackgroundCallerScan {

    static final Pattern GATE_CALL = Pattern.compile(
            "entitlementService\\.(requireRoute|requireRead|requireWrite|requireLimitSlot|precheckLimitSlot)\\(");
    private static final Pattern ENTRY_ANNOTATION = Pattern.compile(
            "^@(Scheduled|Async|EventListener|TransactionalEventListener)\\b.*");
    private static final Pattern METHOD = Pattern.compile(
            "^    (?:public |private |protected |static |final |synchronized )*[\\w<>\\[\\], ?.]+ (\\w+)\\(.*");
    private static final Pattern FIELD = Pattern.compile("\\b([A-Z]\\w+)(?:<[^;(){}]*?>)?\\s+([a-z]\\w*)\\s*[;,)=]");
    private static final Pattern RECEIVER_CALL = Pattern.compile("\\b(\\w+)\\.(\\w+)\\(");
    private static final Pattern METHOD_REF = Pattern.compile("\\b(\\w+)::(\\w+)");
    private static final Pattern PLAIN_CALL = Pattern.compile("(?<![.\\w])(\\w+)\\(");
    private static final Pattern CLASSIFIED = Pattern.compile("@(NotPlanGated|PlanGatedBackground)\\(\\s*reason\\s*=\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern PROGRAMMATIC = Pattern.compile("\\b(ScheduledExecutorService|TaskScheduler|ThreadPoolTaskScheduler)\\b");

    record Node(String cls, String method) {
        @Override public String toString() {
            return cls + "#" + method;
        }
    }

    /** One entry point and how it is classified ({@code null} kind = unclassified). */
    record Entry(Node node, String kind, String reason, boolean reachesGate, List<Node> path) {
    }

    private final Map<String, String> sources;
    private final Map<Node, List<String>> bodies = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> fieldTypes = new HashMap<>();
    private final Map<String, Set<String>> implementors = new HashMap<>();
    private final List<Node> entryNodes = new ArrayList<>();
    private final Map<Node, String[]> classification = new HashMap<>();
    private final Set<Node> gated = new LinkedHashSet<>();

    BackgroundCallerScan(Map<String, String> sources) {
        this.sources = sources;
        sources.forEach(this::index);
    }

    private void index(String cls, String text) {
        Map<String, String> ft = new HashMap<>();
        Matcher fm = FIELD.matcher(text);
        while (fm.find()) {
            ft.put(fm.group(2), fm.group(1));
        }
        fieldTypes.put(cls, ft);
        Matcher im = Pattern.compile("\\bclass\\s+" + cls + "\\b[^{]*?\\b(?:implements|extends)\\s+([^{]+)\\{", Pattern.DOTALL).matcher(text);
        if (im.find()) {
            Matcher t = Pattern.compile("[A-Z]\\w+").matcher(im.group(1).replaceAll("<[^>]*>", ""));
            while (t.find()) {
                implementors.computeIfAbsent(t.group(), k -> new HashSet<>()).add(cls);
            }
        }
        String[] lines = text.split("\n", -1);
        String[] classLevel = classificationAbove(lines, firstLineMatching(lines, "^(?:public |final |abstract )*(?:class|record) " + cls + "\\b.*"));
        boolean programmatic = PROGRAMMATIC.matcher(text).find();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = METHOD.matcher(lines[i]);
            String trimmed = lines[i].trim();
            if (!m.matches() || trimmed.startsWith("return") || trimmed.startsWith("new ") || trimmed.startsWith("throw")) {
                continue;
            }
            Node node = new Node(cls, m.group(1));
            int end = i;
            while (end < lines.length && !lines[end].startsWith("    }")
                    && !(end == i && lines[end].trim().endsWith(";") && !lines[end].contains("{"))) {
                end++;
            }
            List<String> body = new ArrayList<>(List.of(String.join("\n", java.util.Arrays.copyOfRange(lines, i, Math.min(end + 1, lines.length)))));
            bodies.computeIfAbsent(node, k -> new ArrayList<>()).addAll(body);
            boolean annotated = false;
            for (int k = i - 1; k >= 0 && lines[k].trim().startsWith("@"); k--) {
                if (ENTRY_ANNOTATION.matcher(lines[k].trim()).matches()) {
                    annotated = true;
                }
            }
            if (annotated || programmatic) {
                if (!entryNodes.contains(node)) {
                    entryNodes.add(node);
                }
                String[] own = classificationAbove(lines, i);
                classification.put(node, own != null ? own : classLevel);
            }
            i = Math.max(i, end);
        }
        bodies.forEach((n, bs) -> {
            if (n.cls().equals(cls) && bs.stream().anyMatch(b -> GATE_CALL.matcher(b).find())) {
                gated.add(n);
            }
        });
    }

    private static int firstLineMatching(String[] lines, String regex) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].matches(regex)) {
                return i;
            }
        }
        return -1;
    }

    /** The {@code {kind, reason}} of an annotation block directly above line {@code i}, or null. */
    private static String[] classificationAbove(String[] lines, int i) {
        for (int k = i - 1; k >= 0 && k < lines.length && lines[k].trim().startsWith("@"); k--) {
            Matcher m = CLASSIFIED.matcher(lines[k]);
            if (m.find()) {
                return new String[]{m.group(1), m.group(2)};
            }
        }
        return null;
    }

    private Set<Node> callees(Node n) {
        Set<Node> out = new LinkedHashSet<>();
        Map<String, String> ft = fieldTypes.getOrDefault(n.cls(), Map.of());
        for (String body : bodies.getOrDefault(n, List.of())) {
            Matcher m = RECEIVER_CALL.matcher(body);
            while (m.find()) {
                addCall(out, n.cls(), ft, m.group(1), m.group(2));
            }
            m = METHOD_REF.matcher(body);
            while (m.find()) {
                addCall(out, n.cls(), ft, m.group(1), m.group(2));
            }
            m = PLAIN_CALL.matcher(body);
            while (m.find()) {
                Node same = new Node(n.cls(), m.group(1));
                if (bodies.containsKey(same)) {
                    out.add(same);
                }
            }
        }
        out.remove(n);
        return out;
    }

    private void addCall(Set<Node> out, String cls, Map<String, String> ft, String receiver, String method) {
        if (receiver.equals("this")) {
            if (bodies.containsKey(new Node(cls, method))) {
                out.add(new Node(cls, method));
            }
            return;
        }
        String type = ft.get(receiver);
        if (type == null) {
            return;
        }
        Set<String> targets = new HashSet<>(implementors.getOrDefault(type, Set.of()));
        targets.add(type);
        for (String t : targets) {
            Node c = new Node(t, method);
            if (bodies.containsKey(c)) {
                out.add(c);
            }
        }
    }

    /** Every entry point with its classification and whether (and how) it reaches a gated check. */
    List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        for (Node e : entryNodes) {
            Map<Node, Node> parent = new HashMap<>();
            Deque<Node> queue = new ArrayDeque<>(List.of(e));
            parent.put(e, null);
            Node hit = gated.contains(e) ? e : null;
            while (hit == null && !queue.isEmpty()) {
                Node n = queue.poll();
                for (Node c : callees(n)) {
                    if (!parent.containsKey(c)) {
                        parent.put(c, n);
                        queue.add(c);
                        if (gated.contains(c)) {
                            hit = c;
                            break;
                        }
                    }
                }
            }
            List<Node> path = new ArrayList<>();
            for (Node n = hit; n != null; n = parent.get(n)) {
                path.add(0, n);
            }
            String[] c = classification.get(e);
            result.add(new Entry(e, c == null ? null : c[0], c == null ? null : c[1], hit != null, path));
        }
        return result;
    }

    /** Problems: unclassified, blank reason, or a {@code @NotPlanGated} entry that reaches a gated check. */
    List<String> findings() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries()) {
            if (e.kind() == null) {
                out.add(e.node() + " is a background entry point without @NotPlanGated / @PlanGatedBackground");
            } else if (e.reason().isBlank()) {
                out.add(e.node() + " is classified without a reason");
            } else if (e.kind().equals("NotPlanGated") && e.reachesGate()) {
                out.add(e.node() + " is @NotPlanGated but reaches a plan check: " + e.path());
            }
        }
        return out;
    }

    Collection<Node> gatedNodes() {
        return gated;
    }

    Map<String, String> classifiedKinds() {
        Map<String, String> m = new TreeMap<>();
        entries().forEach(e -> m.put(e.node().toString(), e.kind()));
        return m;
    }

    Map<String, String> sources() {
        return sources;
    }
}
