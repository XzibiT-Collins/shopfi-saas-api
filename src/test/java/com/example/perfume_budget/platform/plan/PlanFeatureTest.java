package com.example.perfume_budget.platform.plan;

import com.example.perfume_budget.platform.plan.dto.EntitlementView;
import com.example.perfume_budget.platform.plan.model.PlanEntitlement;
import com.example.perfume_budget.platform.plan.model.PlanEntitlementId;
import com.example.perfume_budget.platform.plan.repository.PlanEntitlementRepository;
import com.example.perfume_budget.platform.plan.repository.PlanRepository;
import com.example.perfume_budget.platform.plan.service.PlanService;
import com.example.perfume_budget.platform.repository.TenantRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PLAN-01 AC4: the code-defined feature catalog (plans.md section 2). */
class PlanFeatureTest {

    @Test
    void sixteenKeys_eachWithKindLabelAndGroup_inTheDocumentedOrder() throws Exception {
        assertThat(PlanFeature.values()).hasSize(16);
        for (PlanFeature f : PlanFeature.values()) {
            assertThat(f.kind()).as(f.name()).isNotNull();
            assertThat(f.label()).as(f.name()).isNotBlank().hasSizeLessThanOrEqualTo(60);
            assertThat(f.group()).as(f.name()).isNotBlank().hasSizeLessThanOrEqualTo(40);
            assertThat(f.name()).matches("^[A-Z][A-Z0-9_]{1,63}$");
        }
        // the section 2 table of plans.md, row for row: key, kind, label, group
        String doc = Files.readString(Path.of("docs/api/plans.md"));
        Matcher m = Pattern.compile("(?m)^\\| `([A-Z_]+)` \\| (BOOLEAN|LIMIT) \\| ([^|]+) \\| ([^|]+) \\|").matcher(doc);
        List<String> documented = new ArrayList<>();
        while (m.find()) {
            documented.add(m.group(1) + "/" + m.group(2) + "/" + m.group(3).strip() + "/" + m.group(4).strip());
        }
        List<String> code = Arrays.stream(PlanFeature.values())
                .map(f -> f.name() + "/" + f.kind() + "/" + f.label() + "/" + f.group()).toList();
        assertThat(code).isEqualTo(documented);
        assertThat(PlanFeature.fromKey("MAX_CUSTOM_DOMAINS")).as("owner O3").isEmpty();
        assertThat(PlanFeature.fromKey("RETURNS_REFUNDS")).as("PO: returns are core").isEmpty();
        assertThat(PlanFeature.fromKey(null)).isEmpty();
        assertThat(PlanFeature.fromKey("walk_in_pos")).as("keys are exact").isEmpty();
    }

    @Test
    void groupsAppearContiguously_inTheOrderOfTheirFirstFeature() {
        List<String> seen = new ArrayList<>();
        String last = null;
        for (PlanFeature f : PlanFeature.values()) {
            if (!f.group().equals(last)) {
                assertThat(seen).as("group " + f.group() + " is split").doesNotContain(f.group());
                seen.add(f.group());
                last = f.group();
            }
        }
        assertThat(seen).containsExactly("Storefront", "Catalog", "Orders", "Marketing", "Inventory",
                "Finance and reports", "Team and security");
    }

    /** A stored key that is not in the enum is ignored on read (and logged), never a crash, never returned. */
    @Test
    void unknownStoredKeyIsIgnoredOnRead_andLogged_missingKeysReadAsOff() {
        PlanRepository plans = mock(PlanRepository.class);
        PlanEntitlementRepository ents = mock(PlanEntitlementRepository.class);
        when(plans.existsById(anyLong())).thenReturn(true);
        when(ents.findByPlanIds(anyCollection())).thenReturn(List.of(
                new PlanEntitlement(new PlanEntitlementId(9L, "RETURNS_REFUNDS"), true, null),
                new PlanEntitlement(new PlanEntitlementId(9L, "MAX_CUSTOM_DOMAINS\n"), true, 5),
                new PlanEntitlement(new PlanEntitlementId(9L, "WALK_IN_POS"), true, null),
                new PlanEntitlement(new PlanEntitlementId(9L, "MAX_PRODUCTS"), true, null)));
        PlanService service = new PlanService(plans, ents, mock(TenantRepository.class));

        Logger logger = (Logger) LoggerFactory.getLogger(PlanService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        List<EntitlementView> list;
        try {
            list = service.entitlements(9L).orElseThrow();
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(list).extracting(EntitlementView::featureKey)
                .containsExactly(Arrays.stream(PlanFeature.values()).map(Enum::name).toArray(String[]::new));
        assertThat(list).filteredOn(e -> e.featureKey().equals("WALK_IN_POS")).singleElement()
                .isEqualTo(new EntitlementView("WALK_IN_POS", true, null));
        assertThat(list).filteredOn(e -> e.featureKey().equals("MAX_PRODUCTS")).singleElement()
                .as("LIMIT null = unlimited, enabled").isEqualTo(new EntitlementView("MAX_PRODUCTS", true, null));
        assertThat(list).filteredOn(e -> e.featureKey().equals("CUSTOM_DOMAIN")).singleElement()
                .as("missing BOOLEAN row = off").isEqualTo(new EntitlementView("CUSTOM_DOMAIN", false, null));
        assertThat(list).filteredOn(e -> e.featureKey().equals("MAX_STAFF")).singleElement()
                .as("missing LIMIT row = 0, disabled").isEqualTo(new EntitlementView("MAX_STAFF", false, 0));
        List<String> warnings = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(warnings).anyMatch(w -> w.contains("RETURNS_REFUNDS") && w.contains("ignored"));
        assertThat(warnings).anyMatch(w -> w.contains("MAX_CUSTOM_DOMAINS?")).noneMatch(w -> w.contains("\n"));
    }
}
