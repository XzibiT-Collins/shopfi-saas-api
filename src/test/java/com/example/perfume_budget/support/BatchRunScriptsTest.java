package com.example.perfume_budget.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MAIN-RED-02 AC1: a batch run with skipped > 0 (or no report at all) is invalid and the script says so with a non-zero
 * exit, and the batch selection covers every test package exactly once.
 */
class BatchRunScriptsTest {

    @TempDir Path tmp;

    private record Result(int exit, String out) {}

    private static Result sh(java.util.List<String> cmd, java.util.Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Result(p.exitValue(), out);
    }

    private Path report(String name, int tests, int skipped) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("reports"));
        Files.writeString(dir.resolve("TEST-" + name + ".xml"), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" name=\"" + name + "\" time=\"0.1\" tests=\""
                + tests + "\" errors=\"0\" skipped=\"" + skipped + "\" failures=\"0\">\n</testsuite>\n");
        return dir;
    }

    private Result check(Path dir) throws Exception {
        return sh(java.util.List.of("sh", "scripts/check-no-skipped.sh", dir.toString()), java.util.Map.of());
    }

    @Test
    void zeroSkippedIsAccepted() throws Exception {
        report("a.A", 5, 0);
        Path dir = report("b.B", 3, 0);
        Result r = check(dir);
        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(r.out()).contains("reports=2 tests=8 skipped=0");
    }

    @Test
    void anySkippedTestMakesTheRunInvalid() throws Exception {
        report("a.A", 5, 0);
        Path dir = report("b.B", 3, 1);
        Result r = check(dir);
        assertThat(r.exit()).as(r.out()).isEqualTo(1);
        assertThat(r.out()).contains("skipped=1").contains("INVALID RUN").contains("TEST-b.B.xml");
    }

    @Test
    void noReportAtAllIsInvalidToo() throws Exception {
        Path empty = Files.createDirectories(tmp.resolve("none"));
        Result r = check(empty);
        assertThat(r.exit()).as(r.out()).isEqualTo(2);
        assertThat(r.out()).contains("no surefire report");
    }

    @Test
    void theFourBatchesPartitionEveryTestPackage() throws Exception {
        Result r = sh(java.util.List.of("sh", "scripts/run-test-batches.sh"), java.util.Map.of("DRY_RUN", "1"));
        assertThat(r.exit()).as(r.out()).isZero();
        java.util.Map<String, String> sel = new java.util.HashMap<>();
        for (String line : r.out().split("\n")) {
            if (line.startsWith("b")) {
                sel.put(line.substring(0, 2), line.substring(3));
            }
        }
        assertThat(sel).containsOnlyKeys("b1", "b2", "b3", "b4");
        Path root = Path.of("src", "test", "java", "com", "example", "perfume_budget");
        try (var pkgs = Files.list(root)) {
            for (Path d : (Iterable<Path>) pkgs.filter(Files::isDirectory)::iterator) {
                String needle = "/perfume_budget/" + d.getFileName() + "/**/*";
                long hits = sel.values().stream().filter(v -> java.util.Arrays.asList(v.split(",")).stream()
                        .anyMatch(x -> x.endsWith(needle))).count();
                assertThat(hits).as("package " + d.getFileName() + " is in exactly one batch").isEqualTo(1);
            }
        }
        assertThat(sel.get("b4")).endsWith("PerfumeBudgetApplicationTests");
    }
}
