package com.example.perfume_budget.platform.billing.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every field and every bound of billing.md 4.1 / 4.2 on the pure parser. The valid body is the contract's
 * {@code settings-update-request} example, so a drift between the doc and the validator fails here first.
 */
public class BillingSettingsParserTest {

    private static final ObjectMapper M = new ObjectMapper();

    public static ObjectNode valid() {
        try {
            String doc = Files.readString(Path.of("docs/api/billing.md"));
            int tag = doc.indexOf("<!-- example: settings-update-request valid");
            int start = doc.indexOf("```json", tag) + 7;
            return (ObjectNode) M.readTree(doc.substring(start, doc.indexOf("```", start)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BillingSettingsParser.Parsed parse(ObjectNode body) {
        return BillingSettingsParser.parse(body, true);
    }

    @Test
    void theContractExampleIsValid() {
        BillingSettingsParser.Parsed p = parse(valid());
        assertThat(p.errors()).isEmpty();
        assertThat(p.data().graceDays()).isEqualTo(7);
        assertThat(p.version()).as("the example is the request of a store at settings version 4").isEqualTo(4);
    }

    static Stream<Arguments> bad() {
        return Stream.of(
                // field, value, message fragment
                a("graceDays", 61, "must be between 0 and 60"), a("graceDays", -1, "must be between 0 and 60"),
                a("graceDays", "7", "must be between 0 and 60"), a("graceDays", 7.5, "must be between 0 and 60"),
                a("graceDays", null, "must be between 0 and 60"),
                a("priceChangeNoticeDays", 366, "must be between 0 and 365"), a("priceChangeNoticeDays", -1, "must be between 0 and 365"),
                a("cardVerificationAmountPesewas", 99, "must be between 100 and 100000"),
                a("cardVerificationAmountPesewas", 100001, "must be between 100 and 100000"),
                a("reconcileIntervalMinutes", 4, "must be between 5 and 1440"), a("reconcileIntervalMinutes", 1441, "must be between 5 and 1440"),
                a("reconcileLookbackHours", 0, "must be between 1 and 720"), a("reconcileLookbackHours", 721, "must be between 1 and 720"),
                a("reconcileFullScanDays", 0, "must be between 1 and 365"), a("reconcileFullScanDays", 366, "must be between 1 and 365"),
                a("billingRunIntervalSeconds", 9, "must be between 10 and 3600"), a("billingRunIntervalSeconds", 3601, "must be between 10 and 3600"),
                a("refundMaxDays", 0, "must be between 1 and 365"), a("refundMaxDays", 366, "must be between 1 and 365"),
                a("refundConfirmThresholdPesewas", -1, "must be between 0 and 100000000"),
                a("refundConfirmThresholdPesewas", 100000001, "must be between 0 and 100000000"),
                a("cardTokenRetentionDaysAfterEnd", -1, "must be between 0 and 365"), a("cardTokenRetentionDaysAfterEnd", 366, "must be between 0 and 365"),
                a("cardAttemptsPerEmailPerDay", 0, "must be between 1 and 50"), a("cardAttemptsPerEmailPerDay", 51, "must be between 1 and 50"),
                a("cardDeclineAlertThresholdPerHour", 0, "must be between 1 and 10000"), a("cardDeclineAlertThresholdPerHour", 10001, "must be between 1 and 10000"),
                a("cardSetupStopThresholdPerHour", 0, "must be between 1 and 100000"), a("cardSetupStopThresholdPerHour", 100001, "must be between 1 and 100000"),
                a("taxRateBps", 10001, "must be between 0 and 10000"), a("taxRateBps", -1, "must be between 0 and 10000"),
                a("billingEnabled", "true", "must be true or false"), a("taxInclusive", 1, "must be true or false"),
                a("activeEnvironment", "test", "must be TEST or LIVE"), a("activeEnvironment", "STAGING", "must be TEST or LIVE"),
                a("retryTimeOfDayUtc", "24:00", "must be HH:mm"), a("retryTimeOfDayUtc", "9:00", "must be HH:mm"), a("retryTimeOfDayUtc", 900, "must be HH:mm"),
                a("invoiceNumberFormat", "INV-{YYYY}", "must contain {NNNNNN}"), a("receiptNumberFormat", "REC {NNNNNN}", "must contain {NNNNNN}"),
                a("creditNoteNumberFormat", "CN-%s-{NNNNNN}", "must contain {NNNNNN}"), 
                a("businessName", "x".repeat(121), "1 to 120 characters"), a("businessAddress", "x".repeat(301), "1 to 300"),
                a("businessTin", "x".repeat(41), "1 to 40"), a("businessPhone", "x".repeat(41), "1 to 40"),
                a("taxLabel", "x".repeat(41), "1 to 40"), a("invoiceFooterText", "x".repeat(501), "1 to 500"),
                a("businessName", "bad‮name", "plain text"), a("businessName", "tab\there", "plain text"),
                a("businessAddress", "line\nbreak", "plain text"), a("invoiceFooterText", "sep x", "plain text"),
                a("taxLabel", "zero​width", "plain text"), a("businessTin", 12, "plain text"),
                a("businessEmail", "not-an-email", "must be an e-mail address"), a("businessEmail", "a b@c.d", "must be an e-mail address"),
                a("emailEnabled", "x", "exactly the notification kinds"));
    }

    private static Arguments a(String field, Object value, String message) {
        return Arguments.of(field, value, message);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("bad")
    void everyBoundIsEnforced(String field, Object value, String message) {
        ObjectNode b = valid();
        b.set(field, M.valueToTree(value));
        BillingSettingsParser.Parsed p = parse(b);
        assertThat(p.errors()).as(field).containsKey(field);
        assertThat(p.errors().get(field)).contains(message);
        assertThat(p.data()).isNull();
    }

    @Test
    void boundariesThemselvesAreAccepted() {
        ObjectNode b = valid();
        b.put("graceDays", 60).put("priceChangeNoticeDays", 0).put("cardVerificationAmountPesewas", 100)
                .put("reconcileIntervalMinutes", 5).put("billingRunIntervalSeconds", 3600).put("refundMaxDays", 365)
                .put("refundConfirmThresholdPesewas", 0).put("cardTokenRetentionDaysAfterEnd", 0)
                .put("cardAttemptsPerEmailPerDay", 50).put("taxRateBps", 10000).put("cardDeclineAlertThresholdPerHour", 100)
                .put("cardSetupStopThresholdPerHour", 100);
        b.putArray("retryOffsetsDays").add(1).add(60);
        assertThat(parse(b).errors()).isEmpty();
        b.put("graceDays", 0);
        b.putArray("retryOffsetsDays");
        assertThat(parse(b).errors()).isEmpty();
        b.put("taxRateBps", 0);
        assertThat(parse(b).data().taxRateBps()).isZero();
    }

    @Test
    void retryOffsetsMustBeStrictlyAscendingWithinGraceAndAtMostSix() {
        for (String bad : new String[]{"[1,3,3]", "[3,1]", "[1,2,3,4,5,6,7]", "[0]", "[8]", "[1.5]", "[\"1\"]", "5"}) {
            ObjectNode b = valid();
            b.put("graceDays", 7);
            b.set("retryOffsetsDays", json(bad));
            assertThat(parse(b).errors()).as(bad).containsKey("retryOffsetsDays");
        }
        ObjectNode ok = valid();
        ok.put("graceDays", 7);
        ok.set("retryOffsetsDays", json("[1,2,3,4,5,6]"));
        assertThat(parse(ok).errors()).isEmpty();
        // graceDays = 0 allows only []
        ObjectNode zero = valid();
        zero.put("graceDays", 0);
        zero.set("retryOffsetsDays", json("[1]"));
        assertThat(parse(zero).errors()).containsKey("retryOffsetsDays");
    }

    @Test
    void reminderListsAreBoundedDistinctAndInRange() {
        record Case(String field, int max) { }
        for (Case c : new Case[]{new Case("trialReminderDaysBefore", 5), new Case("renewalReminderDaysBefore", 5),
                new Case("graceReminderOffsetsDays", 6), new Case("graceEndingReminderDaysBefore", 3)}) {
            ObjectNode tooMany = valid();
            ArrayNode a = tooMany.putArray(c.field());
            for (int i = 1; i <= c.max() + 1; i++) {
                a.add(i);
            }
            assertThat(parse(tooMany).errors()).as(c.field() + " too many").containsKey(c.field());
            for (String bad : new String[]{"[2,2]", "[0]", "[61]", "[\"3\"]"}) {
                ObjectNode b = valid();
                b.set(c.field(), json(bad));
                assertThat(parse(b).errors()).as(c.field() + bad).containsKey(c.field());
            }
            ObjectNode full = valid();
            ArrayNode f = full.putArray(c.field());
            for (int i = 1; i <= c.max(); i++) {
                f.add(i);
            }
            assertThat(parse(full).errors()).as(c.field() + " full").isEmpty();
        }
    }

    @Test
    void emailEnabledNeedsExactlyTheNotificationKinds() {
        ObjectNode missing = valid();
        ((ObjectNode) missing.get("emailEnabled")).remove("CARD_UPDATED");
        assertThat(parse(missing).errors()).containsKey("emailEnabled");
        ObjectNode extra = valid();
        ((ObjectNode) extra.get("emailEnabled")).put("NOT_A_KIND", true);
        assertThat(parse(extra).errors()).containsKey("emailEnabled");
        ObjectNode notBool = valid();
        ((ObjectNode) notBool.get("emailEnabled")).put("CARD_UPDATED", "yes");
        assertThat(parse(notBool).errors()).containsKey("emailEnabled");
    }

    @Test
    void formatsMustDifferInTheirLiteralPart() {
        ObjectNode b = valid();
        b.put("invoiceNumberFormat", "DOC-{YYYY}-{NNNNNN}").put("receiptNumberFormat", "DOC-{YYYY}-{NNNNNN}");
        assertThat(parse(b).errors()).containsKey("receiptNumberFormat");
        ObjectNode c = valid();
        c.put("creditNoteNumberFormat", "SUB-{YYYY}-{NNNNNN}");
        assertThat(parse(c).errors().get("creditNoteNumberFormat")).isEqualTo("formats must differ");
    }

    @Test
    void channelsRecurringSubsetAndIpAllowlist() {
        ObjectNode b = valid();
        b.set("allowedChannels", json("[\"card\",\"card\"]"));
        assertThat(parse(b).errors()).containsKey("allowedChannels");
        b.set("allowedChannels", json("[]"));
        assertThat(parse(b).errors()).containsKey("allowedChannels");
        b.set("allowedChannels", json("[\"cash\"]"));
        assertThat(parse(b).errors()).containsKey("allowedChannels");
        ObjectNode r = valid();
        r.set("allowedChannels", json("[\"card\"]"));
        r.set("recurringChannels", json("[\"card\",\"ussd\"]"));
        assertThat(parse(r).errors().get("recurringChannels")).isEqualTo("must be a non-empty subset of allowedChannels");
        r.set("allowedChannels", json("[\"card\",\"ussd\"]"));
        assertThat(parse(r).errors()).isEmpty();

        ObjectNode ip = valid();
        ip.put("webhookIpAllowlistEnabled", true);
        ip.set("webhookIpAllowlist", json("[]"));
        assertThat(parse(ip).errors().get("webhookIpAllowlist")).isEqualTo("provide at least one address");
        for (String bad : new String[]{"[\"example.com\"]", "[\"1.2.3\"]", "[\"256.1.1.1\"]", "[\"1.2.3.4/33\"]", "[\"::1/129\"]", "[\"1.2.3.4\",\"1.2.3.4\"]", "[\"1.2.3.4/\"]", "[\"zz::1\"]"}) {
            ObjectNode b2 = valid();
            b2.set("webhookIpAllowlist", json(bad));
            assertThat(parse(b2).errors()).as(bad).containsKey("webhookIpAllowlist");
        }
        for (String good : new String[]{"[\"1.2.3.4\"]", "[\"10.0.0.0/8\"]", "[\"2001:db8::/32\"]", "[\"::1\"]"}) {
            ObjectNode g = valid();
            g.set("webhookIpAllowlist", json(good));
            assertThat(parse(g).errors()).as(good).isEmpty();
        }
        ObjectNode tooMany = valid();
        ArrayNode a = tooMany.putArray("webhookIpAllowlist");
        for (int i = 1; i <= 21; i++) {
            a.add("10.0.0." + i);
        }
        assertThat(parse(tooMany).errors()).containsKey("webhookIpAllowlist");
    }

    @Test
    void stopThresholdMustBeAtLeastTheAlertThreshold() {
        ObjectNode b = valid();
        b.put("cardDeclineAlertThresholdPerHour", 50).put("cardSetupStopThresholdPerHour", 49);
        assertThat(parse(b).errors()).containsKey("cardSetupStopThresholdPerHour");
        b.put("cardSetupStopThresholdPerHour", 50);
        assertThat(parse(b).errors()).isEmpty();
    }

    @Test
    void plainTextIsTrimmedBlankBecomesNullAndLengthCountsCodePoints() {
        ObjectNode b = valid();
        b.put("businessName", "  Example Ltd  ").put("businessAddress", "   ").put("taxLabel", "　");
        BillingSettingsParser.Parsed p = parse(b);
        assertThat(p.data().businessName()).isEqualTo("Example Ltd");
        assertThat(p.data().businessAddress()).isNull();
        assertThat(p.data().taxLabel()).isNull();
        // 120 astral characters = 120 code points (240 UTF-16 units): accepted; 121 refused
        b.put("businessName", "😀".repeat(120));
        assertThat(parse(b).errors()).isEmpty();
        b.put("businessName", "😀".repeat(121));
        assertThat(parse(b).errors()).containsKey("businessName");
    }

    @Test
    void versionIsRequiredAndANonNegativeWholeNumber() {
        ObjectNode b = valid();
        b.remove("version");
        assertThat(parse(b).errors()).containsKey("version");
        b.put("version", -1);
        assertThat(parse(b).errors()).containsKey("version");
        b.put("version", "0");
        assertThat(parse(b).errors()).containsKey("version");
    }

    @Test
    void everyViolationIsReportedAtOnce() {
        ObjectNode b = valid();
        b.put("graceDays", 99).put("taxRateBps", 20000).put("refundMaxDays", 0);
        assertThat(parse(b).errors()).containsOnlyKeys("graceDays", "taxRateBps", "refundMaxDays");
    }

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
