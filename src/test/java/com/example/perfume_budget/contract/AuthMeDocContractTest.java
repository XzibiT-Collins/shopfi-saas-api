package com.example.perfume_budget.contract;

import com.example.perfume_budget.dto.auth.response.AuthResponse;
import com.example.perfume_budget.enums.FrontDeskPermission;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.model.FrontDeskPermissionTemplate;
import com.example.perfume_budget.model.FrontDeskUserPermissionOverride;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.FrontDeskPermissionTemplateRepository;
import com.example.perfume_budget.repository.FrontDeskUserPermissionOverrideRepository;
import com.example.perfume_budget.service.FrontDeskAccessServiceImpl;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * API-RD-06 (backend part): docs/api/auth-me.md. The examples parse into the real AuthResponse (unknown properties
 * rejected), the effective-permission rules it states are the code's, and the permission to route table is the set of
 * checks that really exist in the services (so the day a PRODUCT_VIEW_* permission is enforced this test fails on purpose).
 */
class AuthMeDocContractTest {

    private static final Path DOC = Path.of("docs/api/auth-me.md");
    private static final String SRC = "src/main/java/com/example/perfume_budget/";
    private static final ObjectMapper STRICT = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private static Map<String, String> examples() throws Exception {
        Matcher m = Pattern.compile("```json ([a-z-]+)\\n(.*?)```", Pattern.DOTALL).matcher(Files.readString(DOC));
        Map<String, String> out = new LinkedHashMap<>();
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private static AuthResponse auth(String json) throws Exception {
        JsonNode root = STRICT.readTree(json);
        assertThat(root.get("description").asText()).isEqualTo("Authenticated user fetched successfully");
        return STRICT.treeToValue(root.get("data"), AuthResponse.class);
    }

    @Test
    void examplesParseIntoAuthResponse() throws Exception {
        Map<String, String> ex = examples();
        assertThat(ex.keySet()).containsExactly("auth-me-front-desk", "auth-me-admin");
        AuthResponse desk = auth(ex.get("auth-me-front-desk"));
        assertThat(desk.role()).isEqualTo(UserRole.FRONT_DESK);
        assertThat(desk.permissions()).containsExactlyInAnyOrder(FrontDeskPermission.CUSTOMER_SEARCH,
                FrontDeskPermission.WALK_IN_ORDER_CREATE, FrontDeskPermission.WALK_IN_ORDER_VIEW);
        assertThat(desk.profilePicture()).isEmpty();
        AuthResponse admin = auth(ex.get("auth-me-admin"));
        assertThat(admin.role()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.permissions()).isEmpty();
        // the record holds exactly the documented fields (a new field must be documented, a removed one noticed)
        assertThat(Arrays.stream(AuthResponse.class.getRecordComponents()).map(c -> c.getName()).toList())
                .containsExactly("id", "email", "fullName", "profilePicture", "role", "permissions");
    }

    @Test
    void effectivePermissionsFollowTheDocumentedRules() {
        FrontDeskPermissionTemplateRepository templates = mock(FrontDeskPermissionTemplateRepository.class);
        FrontDeskUserPermissionOverrideRepository overrides = mock(FrontDeskUserPermissionOverrideRepository.class);
        FrontDeskAccessServiceImpl svc = new FrontDeskAccessServiceImpl(templates, overrides, null);
        User desk = User.builder().id(1L).roles(UserRole.FRONT_DESK).build();
        when(templates.findAllByEnabledTrue()).thenReturn(List.of(
                template(FrontDeskPermission.WALK_IN_ORDER_CREATE), template(FrontDeskPermission.CUSTOMER_SEARCH)));
        when(overrides.findAllByUser(desk)).thenReturn(List.of(
                override(desk, FrontDeskPermission.STOCK_TRANSFER, true), override(desk, FrontDeskPermission.CUSTOMER_SEARCH, false)));

        // FRONT_DESK: template + allowed overrides - denied overrides
        assertThat(svc.getEffectivePermissions(desk)).containsExactlyInAnyOrder(FrontDeskPermission.WALK_IN_ORDER_CREATE,
                FrontDeskPermission.STOCK_TRANSFER);
        // ADMIN and CUSTOMER: the set is empty (rule 1 and 2) even though the template is not
        assertThat(svc.getEffectivePermissions(User.builder().roles(UserRole.ADMIN).build())).isEmpty();
        assertThat(svc.getEffectivePermissions(User.builder().roles(UserRole.CUSTOMER).build())).isEmpty();
        // ... and the admin still passes every check by role
        for (FrontDeskPermission p : FrontDeskPermission.values()) {
            assertThat(svc.hasPermission(User.builder().roles(UserRole.ADMIN).build(), p)).as(p.name()).isTrue();
            assertThat(svc.hasPermission(User.builder().roles(UserRole.CUSTOMER).build(), p)).as(p.name()).isFalse();
        }
        // rule 3: an empty template and no overrides is a normal empty set
        User fresh = User.builder().id(2L).roles(UserRole.FRONT_DESK).build();
        when(templates.findAllByEnabledTrue()).thenReturn(List.of());
        when(overrides.findAllByUser(fresh)).thenReturn(List.of());
        assertThat(svc.getEffectivePermissions(fresh)).isEmpty();
    }

    private static FrontDeskPermissionTemplate template(FrontDeskPermission p) {
        return FrontDeskPermissionTemplate.builder().permission(p).enabled(true).build();
    }

    private static FrontDeskUserPermissionOverride override(User u, FrontDeskPermission p, boolean allowed) {
        return FrontDeskUserPermissionOverride.builder().user(u).permission(p).allowed(allowed).build();
    }

    @Test
    void permissionTableIsTheSetOfChecksThatExist() throws Exception {
        String doc = Files.readString(DOC);
        // 1. which permissions the services enforce today
        Set<String> enforced = new java.util.TreeSet<>();
        Pattern use = Pattern.compile("(?:requireAccess|requireWalkInAccess)\\(FrontDeskPermission\\.([A-Z_]+)\\)");
        try (Stream<Path> files = Files.walk(Path.of(SRC))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = use.matcher(Files.readString(f));
                while (m.find()) enforced.add(m.group(1));
            }
        }
        assertThat(enforced).containsExactlyInAnyOrder("WALK_IN_ORDER_CREATE", "CUSTOMER_SEARCH", "WALK_IN_ORDER_VIEW",
                "WALK_IN_ORDER_MARK_RECEIPT_PRINTED", "STOCK_TRANSFER", "STOCK_CONVERSION");
        // 2. the doc has a row for every permission; the two unenforced ones say "nothing today", the others do not
        for (FrontDeskPermission p : FrontDeskPermission.values()) {
            Matcher row = Pattern.compile("^\\| `" + p.name() + "` \\|(.*)$", Pattern.MULTILINE).matcher(doc);
            assertThat(row.find()).as("doc row for " + p).isTrue();
            assertThat(row.group(1).contains("**nothing today**")).as("nothing-today flag of " + p).isEqualTo(!enforced.contains(p.name()));
        }
        // 3. no other code reads the two decorative permissions
        try (Stream<Path> files = Files.walk(Path.of(SRC))) {
            String others = files.filter(p -> p.toString().endsWith(".java") && !p.endsWith("FrontDeskPermission.java"))
                    .map(p -> read(p)).collect(Collectors.joining("\n"));
            assertThat(others).doesNotContain("PRODUCT_VIEW_ADMIN_CATALOG").doesNotContain("PRODUCT_VIEW_STOCK_SUMMARY");
        }
        // 4. the 403 texts of the two gates
        assertThat(Files.readString(Path.of(SRC + "service/WalkInOrderServiceImpl.java"))).contains("You do not have permission to perform this walk-in action.");
        assertThat(Files.readString(Path.of(SRC + "service/FrontDeskAccessServiceImpl.java"))).contains("You do not have permission to perform this action.");
        assertThat(doc).contains("You do not have permission to perform this\nwalk-in action.").contains("You do not have permission to perform this action.");
    }

    @Test
    void routeGatesNamedInTheDocExist() throws Exception {
        String doc = Files.readString(DOC);
        String walkIn = Files.readString(Path.of(SRC + "controller/WalkInOrderController.java"));
        // cancel and refund are ADMIN only, everything else walk-in is staff
        assertThat(walkIn.split("@PreAuthorize\\(\"hasRole\\('ADMIN'\\)\"\\)", -1)).hasSize(3);
        assertThat(doc).contains("cancel and refund are `ADMIN` only");
        // /auth/me has no @PreAuthorize (any signed-in tenant user) and returns the shared AuthResponse
        String ctl = Files.readString(Path.of(SRC + "controller/AuthController.java"));
        assertThat(ctl).contains("@GetMapping(\"/me\")").contains("\"Authenticated user fetched successfully\"");
        String svc = Files.readString(Path.of(SRC + "service/AuthServiceImpl.java"));
        assertThat(svc).contains("frontDeskAccessService.getEffectivePermissions(user)").contains(".profilePicture(user.getImageUrl() != null ? user.getImageUrl() : \"\")");
        // the front-desk template is never seeded: no migration or code inserts into it
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/db"))) {
            String sql = files.filter(p -> p.toString().endsWith(".sql")).map(p -> read(p)).collect(Collectors.joining("\n"));
            assertThat(sql.toLowerCase()).doesNotContainPattern("insert\\s+into\\s+front_desk_permission_templates");
        }
        assertThat(doc).contains("Nothing is seeded");
        // rule 4: permissions are never a token claim, they are resolved per call
        String jwt = Files.readString(Path.of(SRC + "filter/JWTUtil.java"));
        assertThat(jwt.toLowerCase()).doesNotContain("permissions");
        assertThat(doc).contains("Permissions are read from the database on every call, not stored in the token.");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
