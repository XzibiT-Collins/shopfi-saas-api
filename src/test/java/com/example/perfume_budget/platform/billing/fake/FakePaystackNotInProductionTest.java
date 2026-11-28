package com.example.perfume_budget.platform.billing.fake;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BILL-01 AC7 / billing.md 19.1 / 21 item 17: the build check that the FAKE Paystack can never ship. Three independent
 * lines: (1) no source file of src/main declares or mentions a fake Paystack class; (2) the compiled production classes
 * ({@code target/classes}, which IS what the jar contains) hold no fake class, checked on a jar built here from exactly that
 * directory the way the Spring Boot plugin packages it; (3) every jar already under {@code target/} (a full
 * {@code package} build) is listed too. The detector itself is proven on a jar that does contain a fake class.
 */
class FakePaystackNotInProductionTest {

    private static final Path MAIN_SRC = Path.of("src/main");
    private static final Path CLASSES = Path.of("target/classes");

    /** What identifies a fake class: its package segment {@code billing/fake} or a class named Fake*Paystack* / *FakePaystack*. */
    static boolean isFakeEntry(String name) {
        String n = name.replace('\\', '/');
        String simple = n.substring(n.lastIndexOf('/') + 1);
        return n.contains("/billing/fake/") || n.contains("billing/fake/") || simple.matches("(?i).*fake.*paystack.*")
                || simple.matches("(?i).*paystack.*fake.*");
    }

    static List<String> fakeEntriesIn(JarFile jar) {
        List<String> found = new ArrayList<>();
        jar.stream().map(JarEntry::getName).filter(FakePaystackNotInProductionTest::isFakeEntry).forEach(found::add);
        return found;
    }

    @Test
    void noMainSourceDeclaresOrMentionsTheFake() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SRC)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String rel = MAIN_SRC.relativize(f).toString();
                if (isFakeEntry(rel)) {
                    offenders.add("file " + rel);
                    continue;
                }
                if (rel.endsWith(".java")) {
                    String src = Files.readString(f);
                    if (src.contains("FakePaystack") || src.contains("billing.fake") || src.contains("FakeCard")) {
                        offenders.add("mentions the fake: " + rel);
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void theProductionClassesDirectoryAndAJarBuiltFromItHoldNoFakeClass() throws IOException {
        assertThat(CLASSES).exists();
        Path jar = Files.createTempFile("production-like", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); Stream<Path> files = Files.walk(CLASSES)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(CLASSES.relativize(f).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(f));
                out.closeEntry();
            }
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            assertThat(jf.stream().count()).as("the jar was built from the production classes").isGreaterThan(300);
            assertThat(fakeEntriesIn(jf)).isEmpty();
        }
        // and the fake really IS a test class: its code source is test-classes, never classes
        String location = FakePaystackServer.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertThat(location).contains("test-classes");
    }

    @Test
    void everyJarOfAFullPackageBuildHoldsNoFakeClass() throws IOException {
        Path target = Path.of("target");
        if (!Files.isDirectory(target)) {
            return;
        }
        try (Stream<Path> jars = Files.list(target)) {
            for (Path j : jars.filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith(".original")).toList()) {
                try (JarFile jf = new JarFile(j.toFile())) {
                    assertThat(fakeEntriesIn(jf)).as(j.toString()).isEmpty();
                }
            }
        }
    }

    @Test
    void theDetectorFindsAFakeClassInAJarThatContainsOne() throws IOException {
        Path jar = Files.createTempFile("with-fake", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : List.of("com/example/perfume_budget/Ok.class", "com/example/perfume_budget/platform/billing/fake/FakePaystackServer.class")) {
                out.putNextEntry(new JarEntry(name));
                out.write(new byte[]{1});
                out.closeEntry();
            }
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            assertThat(fakeEntriesIn(jf)).containsExactly("com/example/perfume_budget/platform/billing/fake/FakePaystackServer.class");
        }
        assertThat(isFakeEntry("com/example/perfume_budget/service/PaystackGatewayServiceImpl.class")).isFalse();
        assertThat(isFakeEntry("BOOT-INF/classes/x/FakePaystackMain.class")).isTrue();
        assertThat(isFakeEntry("x/PaystackFake.class")).isTrue();
    }

    @Test
    void thePomNeverAddsTestClassesToThePackage() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).doesNotContain("test-classes").doesNotContain("<addResources>").doesNotContain("testResources");
        assertThat(pom).doesNotContain("classifier>tests");
    }
}
