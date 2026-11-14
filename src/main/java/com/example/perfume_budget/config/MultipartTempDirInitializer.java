package com.example.perfume_budget.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

/**
 * Prepares the multipart spool area ({@code spring.servlet.multipart.location}, THEME-04c/04d).
 *
 * <p>The configured location is the spool BASE directory. Each JVM spools into its own subdirectory
 * {@code <base>/instance-<pid>-<uuid>}, so a second JVM sharing the base (blue/green, rolling deploy) never empties
 * another JVM's in-flight parts. At startup this class removes only sibling {@code instance-<pid>-*} directories whose
 * process no longer exists (leftovers of a crash or kill); the own subdirectory is removed on shutdown.
 *
 * <p>Safety rules (a wrong {@code MULTIPART_TMP_DIR} must never lead to deleting unrelated files; startup FAILS with
 * a clear message instead of guessing):
 * <ul>
 *   <li>the base is only used when its last path segment is {@value #DIR_NAME} or it contains the marker file
 *       {@value #MARKER}; a missing base is created together with the marker, an existing EMPTY unrecognised
 *       directory is adopted (marker written), a non-empty unrecognised directory is refused;</li>
 *   <li>the filesystem root, the user's home and the system temp directory themselves are always refused;</li>
 *   <li>symbolic links are never followed or deleted through: a symlink as the base (or as any entry) is refused/removed
 *       as a link, never traversed;</li>
 *   <li>on POSIX the base and the instance directory must be owned by the current user and are forced to 0700; if that
 *       is not possible (or the owner differs) startup fails.</li>
 * </ul>
 */
@Slf4j
@Component
public class MultipartTempDirInitializer implements InitializingBean, DisposableBean {

    public static final String DIR_NAME = "shopfi-multipart";
    public static final String MARKER = ".shopfi-multipart";
    static final String INSTANCE_PREFIX = "instance-";

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

    private final String location;
    private final String currentUser;
    private volatile Path instanceDir;

    @org.springframework.beans.factory.annotation.Autowired
    public MultipartTempDirInitializer(@Value("${spring.servlet.multipart.location:}") String location) {
        this(location, System.getProperty("user.name"));
    }

    /** Test seam: the user the directory owner is compared with. */
    MultipartTempDirInitializer(String location, String currentUser) {
        this.location = location;
        this.currentUser = currentUser;
    }

    /** The directory this JVM spools into, or null when no location is configured. Valid after initialisation. */
    public Path instanceDir() {
        return instanceDir;
    }

    @Override
    public void afterPropertiesSet() {
        if (location == null || location.isBlank()) {
            return;
        }
        Path base = Path.of(location).toAbsolutePath().normalize();
        try {
            prepareBase(base);
            reapDeadInstances(base);
            Path mine = base.resolve(INSTANCE_PREFIX + ProcessHandle.current().pid() + "-" + UUID.randomUUID());
            Files.createDirectory(mine, dirAttrs());
            verifyOwnedAndPrivate(mine);
            this.instanceDir = mine;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare multipart spool directory " + base + ": " + e, e);
        }
    }

    @Override
    public void destroy() {
        Path mine = instanceDir;
        if (mine != null) {
            deleteTree(mine);
        }
    }

    // ------------------------------------------------------------------------------------------------ base

    private void prepareBase(Path base) throws IOException {
        refuseDangerous(base);
        if (Files.isSymbolicLink(base)) {
            throw refuse(base, "it is a symbolic link");
        }
        Path parent = base.getParent();
        if (parent == null) {
            throw refuse(base, "it has no parent directory");
        }
        if (Files.notExists(base, LinkOption.NOFOLLOW_LINKS)) {
            // parents are created with default permissions (they are not ours); the spool dir itself atomically 0700
            Files.createDirectories(parent);
            Files.createDirectory(base, dirAttrs());
            Files.createFile(base.resolve(MARKER), fileAttrs());
        } else if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw refuse(base, "it exists and is not a directory");
        } else {
            boolean named = DIR_NAME.equals(String.valueOf(base.getFileName()));
            boolean marked = Files.isRegularFile(base.resolve(MARKER), LinkOption.NOFOLLOW_LINKS);
            if (!named && !marked) {
                if (!isEmpty(base)) {
                    throw refuse(base, "it is not named '" + DIR_NAME + "', has no '" + MARKER
                            + "' marker file and is not empty; point MULTIPART_TMP_DIR at a dedicated directory");
                }
                Files.createFile(base.resolve(MARKER), fileAttrs());
            }
        }
        verifyOwnedAndPrivate(base);
    }

    private static void refuseDangerous(Path base) {
        Path home = System.getProperty("user.home") == null ? null : Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path tmp = System.getProperty("java.io.tmpdir") == null ? null : Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (base.getParent() == null) {
            throw refuse(base, "it is the filesystem root");
        }
        if (base.equals(home)) {
            throw refuse(base, "it is the user's home directory");
        }
        if (base.equals(tmp)) {
            throw refuse(base, "it is the system temp directory itself");
        }
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            return !s.iterator().hasNext();
        }
    }

    /** POSIX: owned by the current user, mode forced to 0700 (fails when impossible). No-op on other file systems. */
    private void verifyOwnedAndPrivate(Path dir) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(dir, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return; // non-POSIX file system
        }
        String owner = view.readAttributes().owner().getName();
        String me = currentUser;
        if (!owner.equals(me)) {
            throw refuse(dir, "it is owned by '" + owner + "', not by the current user '" + me + "'");
        }
        if (!view.readAttributes().permissions().equals(OWNER_ONLY)) {
            view.setPermissions(OWNER_ONLY);
            if (!view.readAttributes().permissions().equals(OWNER_ONLY)) {
                throw refuse(dir, "its mode could not be set to 0700");
            }
        }
    }

    private static java.nio.file.attribute.FileAttribute<?>[] dirAttrs() {
        return supportsPosix() ? new java.nio.file.attribute.FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(OWNER_ONLY)}
                : new java.nio.file.attribute.FileAttribute<?>[0];
    }

    private static java.nio.file.attribute.FileAttribute<?>[] fileAttrs() {
        return supportsPosix() ? new java.nio.file.attribute.FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new java.nio.file.attribute.FileAttribute<?>[0];
    }

    private static boolean supportsPosix() {
        return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static IllegalStateException refuse(Path dir, String why) {
        return new IllegalStateException("Refusing to use " + dir + " as the multipart spool directory: " + why
                + ". Fix or unset MULTIPART_TMP_DIR / spring.servlet.multipart.location.");
    }

    // ------------------------------------------------------------------------------------------------ cleanup

    /** Removes {@code instance-<pid>-*} children whose pid is not a live process; never touches anything else. */
    private static void reapDeadInstances(Path base) throws IOException {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(base, INSTANCE_PREFIX + "*")) {
            for (Path p : s) {
                if (Files.isSymbolicLink(p)) {
                    Files.deleteIfExists(p); // remove the link itself, never what it points to
                    continue;
                }
                if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Long pid = pidOf(p.getFileName().toString());
                if (pid != null && ProcessHandle.of(pid).isEmpty()) {
                    deleteTree(p);
                }
            }
        }
    }

    static Long pidOf(String name) {
        if (!name.startsWith(INSTANCE_PREFIX)) {
            return null;
        }
        String rest = name.substring(INSTANCE_PREFIX.length());
        int dash = rest.indexOf('-');
        if (dash <= 0) {
            return null;
        }
        try {
            return Long.parseLong(rest.substring(0, dash));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Recursive delete that never follows symbolic links (a link is removed as a link). */
    private static void deleteTree(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Files.deleteIfExists(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            }); // walkFileTree does not follow links unless FOLLOW_LINKS is passed
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not fully remove stale multipart spool directory {}", root.getFileName());
        }
    }
}
