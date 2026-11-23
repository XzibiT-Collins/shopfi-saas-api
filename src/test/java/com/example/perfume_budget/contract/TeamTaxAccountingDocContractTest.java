package com.example.perfume_budget.contract;

import com.example.perfume_budget.dto.accounts.request.ManualJournalEntryRequest;
import com.example.perfume_budget.dto.accounts.response.AccountingMetadataResponse;
import com.example.perfume_budget.dto.accounts.response.BalanceSheetResponse;
import com.example.perfume_budget.dto.accounts.response.IncomeStatementResponse;
import com.example.perfume_budget.dto.accounts.response.LedgerSummaryResponse;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskPermissionTemplateResponse;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskUserPermissionsResponse;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskUserSummaryResponse;
import com.example.perfume_budget.dto.front_desk.request.CreateFrontDeskUserRequest;
import com.example.perfume_budget.dto.front_desk.request.FrontDeskPermissionTemplateUpdateRequest;
import com.example.perfume_budget.dto.front_desk.request.FrontDeskUserPermissionOverrideUpdateRequest;
import com.example.perfume_budget.dto.front_desk.request.UpdateFrontDeskUserRequest;
import com.example.perfume_budget.dto.tax.request.TaxRequest;
import com.example.perfume_budget.dto.tax.response.TaxAndDiscountCalculationResponse;
import com.example.perfume_budget.dto.tax.response.TaxResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-RD-11 / API-RD-12 (API-RD-02 team, tax, accounting): every named JSON example in docs/api/team-staff.md, tax.md and
 * accounting.md parses into the real records with unknown properties REJECTED, and every documented error code exists in
 * code (and every coded error in code is documented).
 */
class TeamTaxAccountingDocContractTest {

    private static final ObjectMapper STRICT = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private static final String SRC = "src/main/java/com/example/perfume_budget/";

    private static Map<String, String> examples(String doc) throws Exception {
        Matcher m = Pattern.compile("```json ([a-z-]+)\\n(.*?)```", Pattern.DOTALL).matcher(Files.readString(Path.of("docs/api/" + doc)));
        Map<String, String> out = new LinkedHashMap<>();
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private static JsonNode data(String json) throws Exception {
        return STRICT.readTree(json).get("data");
    }

    @Test
    void teamExamplesMatchTheRecords() throws Exception {
        Map<String, String> ex = examples("team-staff.md");
        assertThat(ex.keySet()).containsExactly("template-response", "user-permissions-response", "staff-list",
                "create-staff-request", "update-staff-request", "template-request", "user-permissions-request");
        FrontDeskPermissionTemplateResponse t = STRICT.treeToValue(data(ex.get("template-response")), FrontDeskPermissionTemplateResponse.class);
        assertThat(t.permissions()).hasSize(3);
        assertThat(t.version()).hasSize(32);
        FrontDeskUserPermissionsResponse p = STRICT.treeToValue(data(ex.get("user-permissions-response")), FrontDeskUserPermissionsResponse.class);
        assertThat(p.version()).hasSize(32);
        assertThat(p.effectivePermissions()).hasSize(3);
        List<FrontDeskUserSummaryResponse> list = STRICT.readValue(data(ex.get("staff-list")).toString(), new TypeReference<>() {});
        assertThat(list.get(0).permissionSummary().isCustom()).isTrue();
        assertThat(list.get(0).permissionSummary().totalCount()).isEqualTo(8);
    }

    @Test
    void taxExampleMatchesTheRecord() throws Exception {
        Map<String, String> ex = examples("tax.md");
        assertThat(ex.keySet()).containsExactly("tax-response", "tax-calculation-response", "tax-request");
        TaxResponse t = STRICT.treeToValue(data(ex.get("tax-response")), TaxResponse.class);
        assertThat(t.version()).hasSize(32);
        assertThat(t.isActive()).isTrue();
        // API-RD-02: request body and the calculation response
        TaxRequest req = STRICT.readValue(ex.get("tax-request"), TaxRequest.class);
        assertThat(req.rate()).isEqualByComparingTo("15.00");
        assertThat(req.applyOrder()).isEqualTo(1);
        TaxAndDiscountCalculationResponse calc = STRICT.treeToValue(data(ex.get("tax-calculation-response")), TaxAndDiscountCalculationResponse.class);
        assertThat(calc.orderTaxes()).hasSize(1);
        assertThat(calc.totalAmountAfterTax()).isEqualTo("GHS 115.00");
    }

    @Test
    void requestProseIsPinnedToCode() throws Exception {
        String team = Files.readString(Path.of("docs/api/team-staff.md"));
        String tax = Files.readString(Path.of("docs/api/tax.md"));
        // permission conflict: code, status and the doc's request-section sentence
        assertThat(team).contains("`400 PERMISSION_CONFLICT`");
        assertThat(Files.readString(Path.of(SRC + "service/AdminFrontDeskManagementServiceImpl.java")))
                .contains("cannot be both allowed and denied.\", \"PERMISSION_CONFLICT\"");
        // the example password is an obvious placeholder, not a plausible credential
        assertThat(team).doesNotContain("Str0ng").contains("<choose-a-strong-password>");
        // tax request validation messages and the percentage rule
        String req = Files.readString(Path.of(SRC + "dto/tax/request/TaxRequest.java"));
        for (String msg : new String[]{"Tax name is required", "Tax code is required", "Tax rate is required",
                "Tax rate cannot be negative", "Apply order is required"}) {
            assertThat(req).as(msg).contains("\"" + msg + "\"");
            assertThat(tax).as(msg).contains("`" + msg + "`");
        }
        assertThat(req).contains("@DecimalMin(value = \"0.0\"");
    }

    @Test
    void teamRequestExamplesMatchTheRequestRecords() throws Exception {
        Map<String, String> ex = examples("team-staff.md");
        CreateFrontDeskUserRequest create = STRICT.readValue(ex.get("create-staff-request"), CreateFrontDeskUserRequest.class);
        assertThat(create.isActive()).isTrue();
        assertThat(create.password()).hasSizeGreaterThanOrEqualTo(8);
        UpdateFrontDeskUserRequest update = STRICT.readValue(ex.get("update-staff-request"), UpdateFrontDeskUserRequest.class);
        assertThat(update.isActive()).isFalse();
        FrontDeskPermissionTemplateUpdateRequest template = STRICT.readValue(ex.get("template-request"), FrontDeskPermissionTemplateUpdateRequest.class);
        assertThat(template.permissions()).hasSize(3);
        FrontDeskUserPermissionOverrideUpdateRequest overrides = STRICT.readValue(ex.get("user-permissions-request"), FrontDeskUserPermissionOverrideUpdateRequest.class);
        assertThat(overrides.allowedPermissions()).containsExactly(com.example.perfume_budget.enums.FrontDeskPermission.STOCK_TRANSFER);
        assertThat(overrides.deniedPermissions()).containsExactly(com.example.perfume_budget.enums.FrontDeskPermission.CUSTOMER_SEARCH);
    }

    @Test
    void accountingExamplesMatchTheRecords() throws Exception {
        Map<String, String> ex = examples("accounting.md");
        assertThat(ex.keySet()).containsExactly("summary-response", "income-statement-response", "balance-sheet-response",
                "metadata-response", "manual-entry-request");
        assertThat(STRICT.treeToValue(data(ex.get("summary-response")), LedgerSummaryResponse.class).currency()).isEqualTo("GHS");
        assertThat(STRICT.treeToValue(data(ex.get("income-statement-response")), IncomeStatementResponse.class).revenueAccounts()).hasSize(1);
        BalanceSheetResponse bs = STRICT.treeToValue(data(ex.get("balance-sheet-response")), BalanceSheetResponse.class);
        assertThat(bs.equityAccounts().stream().map(a -> a.accountCode()).toList()).containsExactly("3200", "RETAINED_EARNINGS");
        assertThat(STRICT.treeToValue(data(ex.get("metadata-response")), AccountingMetadataResponse.class).journalEntryTypes()).hasSize(2);
        ManualJournalEntryRequest r = STRICT.readValue(ex.get("manual-entry-request"), ManualJournalEntryRequest.class);
        assertThat(r.type().name()).isEqualTo("EXPENSE");
        assertThat(r.lines()).hasSize(2);
    }

    @Test
    void documentedCodesExistInCodeAndCodedErrorsAreDocumented() throws Exception {
        String team = Files.readString(Path.of("docs/api/team-staff.md"));
        String tax = Files.readString(Path.of("docs/api/tax.md"));
        String acct = Files.readString(Path.of("docs/api/accounting.md"));
        String teamSvc = Files.readString(Path.of(SRC + "service/AdminFrontDeskManagementServiceImpl.java"));
        String taxSvc = Files.readString(Path.of(SRC + "service/TaxServiceImpl.java"));
        String bookkeeping = Files.readString(Path.of(SRC + "service/BookkeepingService.java"));
        String tag = Files.readString(Path.of(SRC + "utils/ConcurrencyTag.java"));

        for (String code : new String[]{"EMAIL_ALREADY_EXISTS", "FULL_NAME_REQUIRED", "EMAIL_REQUIRED", "PASSWORD_TOO_SHORT", "PASSWORD_TOO_WEAK",
                "PASSWORD_TOO_LONG", "NOT_FRONT_DESK_USER", "PERMISSION_CONFLICT", "USER_NOT_FOUND", "TEMPLATE_VERSION_CONFLICT",
                "USER_PERMISSIONS_VERSION_CONFLICT"}) {
            assertThat(team).as(code).contains("`" + code + "`");
            assertThat(teamSvc).as(code).contains("\"" + code + "\"");
        }
        for (String code : new String[]{"TAX_NAME_EXISTS", "TAX_CODE_EXISTS", "TAX_NOT_FOUND", "TAX_VERSION_CONFLICT"}) {
            assertThat(tax).as(code).contains("`" + code + "`");
            assertThat(taxSvc).as(code).contains("\"" + code + "\"");
        }
        for (String doc : new String[]{team, tax}) {
            assertThat(doc).contains("`INVALID_IF_MATCH`");
        }
        assertThat(tag).contains("\"INVALID_IF_MATCH\"");
        for (String code : new String[]{"INVALID_AMOUNT", "IDEMPOTENCY_KEY_INVALID", "IDEMPOTENCY_KEY_REUSED"}) {
            assertThat(acct).as(code).contains(code);
            assertThat(bookkeeping).as(code).contains("\"" + code + "\"");
        }
        // every code the two services throw is in the docs table
        Matcher m = Pattern.compile("\"([A-Z][A-Z_]{5,})\"").matcher(teamSvc);
        while (m.find()) assertThat(team).as(m.group(1)).contains(m.group(1));
        m = Pattern.compile("\"([A-Z][A-Z_]{5,})\"").matcher(taxSvc);
        while (m.find()) assertThat(tax).as(m.group(1)).contains(m.group(1));
        // the migration the manual-entry idempotency docs name exists
        assertThat(Files.exists(Path.of("src/main/resources/db/migrations/tenant/V20__manual_entry_idempotency.sql"))).isTrue();
        assertThat(acct).contains("RETAINED_EARNINGS").contains("INVALID_PAGE");
    }
}
