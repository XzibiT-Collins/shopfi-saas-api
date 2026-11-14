package com.example.perfume_budget.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every directory touched here lives under a JUnit @TempDir; nothing outside it is ever created, emptied or deleted. */
class MultipartTempDirInitializerTest {

    @TempDir Path root;

    private static MultipartTempDirInitializer init(Path dir) {
        MultipartTempDirInitializer i = new MultipartTempDirInitializer(dir.toString());
        i.afterPropertiesSet();
        return i;
    }

    private static long count(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.count();
        }
    }

    @Test
    void blankLocation_isANoOp() {
        MultipartTempDirInitializer i = new MultipartTempDirInitializer(" ");
        i.afterPropertiesSet();
        assertThat(i.instanceDir()).isNull();
    }

    @Test
    void createsMissingBase_withMarker_0700_andAPerInstanceSubdirectory() throws Exception {
        Path base = root.resolve("a/b/shopfi-multipart");
        MultipartTempDirInitializer i = init(base);
        assertThat(base).isDirectory();
        assertThat(base.resolve(MultipartTempDirInitializer.MARKER)).isRegularFile();
        assertThat(Files.getPosixFilePermissions(base)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(i.instanceDir().getParent()).isEqualTo(base);
        assertThat(i.instanceDir().getFileName().toString()).startsWith("instance-" + ProcessHandle.current().pid() + "-");
        assertThat(Files.getPosixFilePermissions(i.instanceDir())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void twoInstancesGetDistinctSubdirectories_andOneNeverEmptiesTheOther() throws Exception {
        Path base = root.resolve("shopfi-multipart");
        MultipartTempDirInitializer first = init(base);
        Files.writeString(first.instanceDir().resolve("part1.tmp"), "in-flight");
        MultipartTempDirInitializer second = init(base); // same live PID here, so the sibling is a live instance
        assertThat(second.instanceDir()).isNotEqualTo(first.instanceDir());
        assertThat(first.instanceDir().resolve("part1.tmp")).hasContent("in-flight");
    }

    @Test
    void reapsInstanceDirectoriesOfDeadProcesses_only() throws Exception {
        Path base = root.resolve("shopfi-multipart");
        init(base);
        Path dead = Files.createDirectory(base.resolve("instance-2147483646-dead"));
        Files.writeString(dead.resolve("orphan.tmp"), "x");
        Path live = Files.createDirectory(base.resolve("instance-" + ProcessHandle.current().pid() + "-live"));
        Files.writeString(live.resolve("keep.tmp"), "x");
        Path unrelated = Files.createDirectory(base.resolve("something-else"));
        Files.writeString(unrelated.resolve("keep2.tmp"), "x");
        Files.writeString(base.resolve("loose-file"), "x");
        init(base);
        assertThat(dead).doesNotExist();
        assertThat(live.resolve("keep.tmp")).exists();
        assertThat(unrelated.resolve("keep2.tmp")).exists();
        assertThat(base.resolve("loose-file")).exists();
    }

    @Test
    void reaping_neverFollowsSymlinks() throws Exception {
        Path base = root.resolve("shopfi-multipart");
        init(base);
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("precious.txt"), "keep");
        Path dead = Files.createDirectory(base.resolve("instance-2147483646-dead"));
        Files.createSymbolicLink(dead.resolve("link"), outside);
        Files.createSymbolicLink(base.resolve("instance-2147483645-linked"), outside);
        init(base);
        assertThat(dead).doesNotExist();
        assertThat(base.resolve("instance-2147483645-linked")).doesNotExist();
        assertThat(outside.resolve("precious.txt")).hasContent("keep");
    }

    @Test
    void refusesASymlinkAsTheBase_andLeavesTheTargetUntouched() throws Exception {
        Path target = Files.createDirectory(root.resolve("target"));
        Files.writeString(target.resolve("precious.txt"), "keep");
        Path link = root.resolve("shopfi-multipart");
        Files.createSymbolicLink(link, target);
        assertThatThrownBy(() -> init(link)).isInstanceOf(IllegalStateException.class).hasMessageContaining("symbolic link");
        assertThat(count(target)).isEqualTo(1);
    }

    @Test
    void refusesANonEmptyDirectoryThatIsNotRecognisedAsOurs() throws Exception {
        Path other = Files.createDirectory(root.resolve("documents"));
        Files.writeString(other.resolve("important.txt"), "keep");
        Files.createDirectory(other.resolve("sub"));
        assertThatThrownBy(() -> init(other)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MULTIPART_TMP_DIR").hasMessageContaining("shopfi-multipart");
        assertThat(other.resolve("important.txt")).hasContent("keep");
        assertThat(count(other)).isEqualTo(2);
    }

    @Test
    void acceptsAnUnnamedDirectoryThatCarriesTheMarker_andAdoptsAnEmptyOne() throws Exception {
        Path marked = Files.createDirectory(root.resolve("custom"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createFile(marked.resolve(MultipartTempDirInitializer.MARKER));
        assertThat(init(marked).instanceDir()).isNotNull();
        Path empty = Files.createDirectory(root.resolve("fresh"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        assertThat(init(empty).instanceDir()).isNotNull();
        assertThat(empty.resolve(MultipartTempDirInitializer.MARKER)).exists();
    }

    @Test
    void refusesAFileAtTheLocation() throws Exception {
        Path file = Files.writeString(root.resolve("shopfi-multipart"), "not a dir");
        assertThatThrownBy(() -> init(file)).isInstanceOf(IllegalStateException.class).hasMessageContaining("not a directory");
        assertThat(file).hasContent("not a dir");
    }

    @Test
    void refusesFilesystemRoot_home_andTheTempDirItself() throws Exception {
        assertThatThrownBy(() -> init(Path.of("/"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("filesystem root");
        String oldHome = System.getProperty("user.home");
        String oldTmp = System.getProperty("java.io.tmpdir");
        Path fakeHome = Files.createDirectory(root.resolve("home"));
        Path fakeTmp = Files.createDirectory(root.resolve("tmp"));
        Files.writeString(fakeHome.resolve("dotfile"), "keep");
        Files.writeString(fakeTmp.resolve("other.tmp"), "keep");
        try {
            System.setProperty("user.home", fakeHome.toString());
            System.setProperty("java.io.tmpdir", fakeTmp.toString());
            assertThatThrownBy(() -> init(fakeHome)).isInstanceOf(IllegalStateException.class).hasMessageContaining("user's home directory");
            assertThatThrownBy(() -> init(fakeTmp)).isInstanceOf(IllegalStateException.class).hasMessageContaining("system temp directory itself");
        } finally {
            System.setProperty("user.home", oldHome);
            System.setProperty("java.io.tmpdir", oldTmp);
        }
        assertThat(fakeHome.resolve("dotfile")).exists();
        assertThat(fakeTmp.resolve("other.tmp")).exists();
    }

    @Test
    void reappliesMode0700_toAnExistingWorldReadableBase() throws Exception {
        Path base = Files.createDirectory(root.resolve("shopfi-multipart"));
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwxrwxrwx"));
        init(base);
        assertThat(Files.getPosixFilePermissions(base)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void refusesADirectoryOwnedBySomeoneElse_withoutTouchingIt() throws Exception {
        // Another owner cannot be created without root, so the current user is faked: the directory then "belongs to
        // somebody else" from the initializer's point of view.
        Path base = Files.createDirectory(root.resolve("shopfi-multipart"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(base.resolve("theirs.tmp"), "keep");
        MultipartTempDirInitializer i = new MultipartTempDirInitializer(base.toString(), "someone-else");
        assertThatThrownBy(i::afterPropertiesSet).isInstanceOf(IllegalStateException.class).hasMessageContaining("owned by");
        assertThat(base.resolve("theirs.tmp")).hasContent("keep");
        assertThat(i.instanceDir()).isNull();
    }

    @Test
    void destroy_removesOnlyTheOwnInstanceDirectory() throws Exception {
        Path base = root.resolve("shopfi-multipart");
        MultipartTempDirInitializer a = init(base);
        MultipartTempDirInitializer b = init(base);
        Files.writeString(a.instanceDir().resolve("p.tmp"), "x");
        a.destroy();
        assertThat(a.instanceDir()).doesNotExist();
        assertThat(b.instanceDir()).isDirectory();
        assertThat(base.resolve(MultipartTempDirInitializer.MARKER)).exists();
    }

    @Test
    void pidOf_parsesOnlyOurInstanceNames() {
        assertThat(MultipartTempDirInitializer.pidOf("instance-123-abc")).isEqualTo(123L);
        assertThat(MultipartTempDirInitializer.pidOf("instance-x-abc")).isNull();
        assertThat(MultipartTempDirInitializer.pidOf("instance-")).isNull();
        assertThat(MultipartTempDirInitializer.pidOf("xyz")).isNull(); // shorter than the prefix: must not throw
    }
}
