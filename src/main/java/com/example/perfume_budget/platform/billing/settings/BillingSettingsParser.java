package com.example.perfume_budget.platform.billing.settings;

import com.example.perfume_budget.platform.billing.BillingEnvironment;
import com.example.perfume_budget.platform.billing.NotificationKind;
import com.example.perfume_budget.platform.plan.PlainText;
import com.example.perfume_budget.platform.plan.VisibleText;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict parser and validator of the settings PUT body (billing.md 4.1 and 4.2): reads the JSON tree itself, so a string
 * where a number belongs, {@code 7.0} for an integer or a missing field is a field error and never silently coerced. Pure
 * (no I/O): the rules that need stored state (credentials VERIFIED) are applied by the service on top.
 *
 * <p>Every violation is {@code field -> message} in the 400 field map; nothing is thrown at the first problem so the console
 * can show them all. List items are indexed in the field name where one item is at fault only for the cross-field rule
 * of {@code retryOffsetsDays}; the per-list messages are the contract's.
 */
public final class BillingSettingsParser {

    public static final Set<String> CHANNELS = Set.of("card", "mobile_money", "bank", "ussd", "qr", "bank_transfer");
    private static final Pattern TIME = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

    /** {@code activeEnvironment} and {@code billingEnabled} are filled even when other fields failed: the service merges its state rules into ONE field map. */
    public record Parsed(Map<String, String> errors, BillingSettingsData data, Integer version,
                         BillingEnvironment activeEnvironment, Boolean billingEnabled) {
    }

    private final JsonNode body;
    private final Map<String, String> errors = new LinkedHashMap<>();

    private BillingSettingsParser(JsonNode body) {
        this.body = body;
    }

    /** {@code requireVersion}: the PUT body carries the lock input. */
    public static Parsed parse(JsonNode body, boolean requireVersion) {
        return new BillingSettingsParser(body).run(requireVersion);
    }

    private Parsed run(boolean requireVersion) {
        boolean billingEnabled = bool("billingEnabled");
        BillingEnvironment env = environment();
        boolean cardRequired = bool("cardRequiredAtSignup");
        Integer graceDays = integer("graceDays", 0, 60, "must be between 0 and 60");
        List<Integer> retry = retryOffsets(graceDays);
        String retryTime = retryTime();
        List<Integer> trial = distinctDays("trialReminderDaysBefore", 5);
        List<Integer> renewal = distinctDays("renewalReminderDaysBefore", 5);
        Integer priceNotice = integer("priceChangeNoticeDays", 0, 365, "must be between 0 and 365");
        List<Integer> graceRem = distinctDays("graceReminderOffsetsDays", 6);
        List<Integer> graceEnd = distinctDays("graceEndingReminderDaysBefore", 3);
        Map<String, Boolean> emails = emailEnabled();
        String invoiceFmt = format("invoiceNumberFormat");
        String receiptFmt = format("receiptNumberFormat");
        String creditFmt = format("creditNoteNumberFormat");
        if (invoiceFmt != null && receiptFmt != null && NumberFormats.literalPart(invoiceFmt).equals(NumberFormats.literalPart(receiptFmt))) {
            errors.putIfAbsent("receiptNumberFormat", "formats must differ");
        }
        if (creditFmt != null && ((invoiceFmt != null && NumberFormats.literalPart(creditFmt).equals(NumberFormats.literalPart(invoiceFmt)))
                || (receiptFmt != null && NumberFormats.literalPart(creditFmt).equals(NumberFormats.literalPart(receiptFmt))))) {
            errors.putIfAbsent("creditNoteNumberFormat", "formats must differ");
        }
        String name = text("businessName", 120);
        String address = text("businessAddress", 300);
        String tin = text("businessTin", 40);
        String email = text("businessEmail", 254);
        if (email != null && !EMAIL.matcher(email).matches()) {
            errors.putIfAbsent("businessEmail", "must be an e-mail address");
        }
        String phone = text("businessPhone", 40);
        String taxLabel = text("taxLabel", 40);
        Integer taxRate = body.hasNonNull("taxRateBps") ? integer("taxRateBps", 0, 10000, "must be between 0 and 10000")
                : nullOrMissing("taxRateBps");
        boolean taxInclusive = bool("taxInclusive");
        String footer = text("invoiceFooterText", 500);
        boolean attachPdf = bool("attachPdfToEmail");
        Long verifyAmount = longValue("cardVerificationAmountPesewas", 100, 100_000, "must be between 100 and 100000");
        List<String> allowed = channels("allowedChannels");
        List<String> recurring = channels("recurringChannels");
        if (allowed != null && recurring != null && !errors.containsKey("recurringChannels") && !allowed.containsAll(recurring)) {
            errors.put("recurringChannels", "must be a non-empty subset of allowedChannels");
        }
        Integer reconInterval = integer("reconcileIntervalMinutes", 5, 1440, "must be between 5 and 1440");
        Integer reconLookback = integer("reconcileLookbackHours", 1, 720, "must be between 1 and 720");
        Integer reconFull = integer("reconcileFullScanDays", 1, 365, "must be between 1 and 365");
        Integer runInterval = integer("billingRunIntervalSeconds", 10, 3600, "must be between 10 and 3600");
        boolean ipEnabled = bool("webhookIpAllowlistEnabled");
        List<String> ips = ipAllowlist();
        if (ipEnabled && ips != null && ips.isEmpty() && !errors.containsKey("webhookIpAllowlist")) {
            errors.put("webhookIpAllowlist", "provide at least one address");
        }
        Integer refundDays = integer("refundMaxDays", 1, 365, "must be between 1 and 365");
        Long refundConfirm = longValue("refundConfirmThresholdPesewas", 0, 100_000_000, "must be between 0 and 100000000");
        Integer tokenDays = integer("cardTokenRetentionDaysAfterEnd", 0, 365, "must be between 0 and 365");
        boolean oneTrial = bool("oneTrialPerCard");
        Integer cardEmailDay = integer("cardAttemptsPerEmailPerDay", 1, 50, "must be between 1 and 50");
        Integer alert = integer("cardDeclineAlertThresholdPerHour", 1, 10_000, "must be between 1 and 10000");
        Integer stop = integer("cardSetupStopThresholdPerHour", 1, 100_000, "must be between 1 and 100000 and at least the alert threshold");
        if (stop != null && alert != null && stop < alert) {
            errors.put("cardSetupStopThresholdPerHour", "must be between 1 and 100000 and at least the alert threshold");
        }
        Integer version = null;
        if (requireVersion) {
            version = integer("version", 0, Integer.MAX_VALUE, "must be a non-negative whole number");
        }
        if (!errors.isEmpty()) {
            return new Parsed(errors, null, version, env, body.get("billingEnabled") != null && body.get("billingEnabled").isBoolean()
                    ? body.get("billingEnabled").booleanValue() : null);
        }
        BillingSettingsData d = new BillingSettingsData(billingEnabled, env, cardRequired, graceDays, retry, retryTime, trial,
                renewal, priceNotice, graceRem, graceEnd, emails, invoiceFmt, receiptFmt, creditFmt, name, address, tin, email,
                phone, taxLabel, taxRate, taxInclusive, footer, attachPdf, verifyAmount, allowed, recurring, reconInterval,
                reconLookback, reconFull, runInterval, ipEnabled, ips, refundDays, tokenDays, oneTrial, cardEmailDay, alert, stop,
                refundConfirm);
        return new Parsed(errors, d, version, env, billingEnabled);
    }

    // ------------------------------------------------------------------------------------------ field readers

    private boolean bool(String f) {
        JsonNode n = body.get(f);
        if (n == null || !n.isBoolean()) {
            errors.put(f, "must be true or false");
            return false;
        }
        return n.booleanValue();
    }

    private BillingEnvironment environment() {
        JsonNode n = body.get("activeEnvironment");
        if (n != null && n.isTextual()) {
            for (BillingEnvironment e : BillingEnvironment.values()) {
                if (e.name().equals(n.textValue())) {
                    return e;
                }
            }
        }
        errors.put("activeEnvironment", "must be TEST or LIVE");
        return null;
    }

    private Integer integer(String f, int min, int max, String message) {
        JsonNode n = body.get(f);
        if (n == null || !n.isIntegralNumber() || !n.canConvertToInt()) {
            errors.put(f, message);
            return null;
        }
        int v = n.intValue();
        if (v < min || v > max) {
            errors.put(f, message);
            return null;
        }
        return v;
    }

    private Long longValue(String f, long min, long max, String message) {
        JsonNode n = body.get(f);
        if (n == null || !n.isIntegralNumber() || !n.canConvertToLong()) {
            errors.put(f, message);
            return null;
        }
        long v = n.longValue();
        if (v < min || v > max) {
            errors.put(f, message);
            return null;
        }
        return v;
    }

    private Integer nullOrMissing(String f) {
        JsonNode n = body.get(f);
        if (n == null || n.isNull()) {
            if (n == null) {
                errors.put(f, "is required (send null for none)");
            }
            return null;
        }
        return null;
    }

    private List<Integer> retryOffsets(Integer graceDays) {
        String shape = "must be strictly ascending, at most 6 values, each between 1 and graceDays";
        String cross = "every retry day must be <= graceDays and the list must be strictly ascending";
        List<Integer> list = intArray("retryOffsetsDays", 6, 1, 60, shape);
        if (list == null) {
            return null;
        }
        boolean ascending = true;
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i) <= list.get(i - 1)) {
                ascending = false;
            }
        }
        boolean withinGrace = graceDays == null || list.stream().allMatch(v -> v <= graceDays);
        if (!ascending || !withinGrace) {
            errors.put("retryOffsetsDays", cross);
            return null;
        }
        return list;
    }

    private String retryTime() {
        JsonNode n = body.get("retryTimeOfDayUtc");
        if (n == null) {
            errors.put("retryTimeOfDayUtc", "must be HH:mm (00:00 to 23:59)");
            return null;
        }
        if (n.isNull()) {
            return null;
        }
        if (!n.isTextual() || !TIME.matcher(n.textValue()).matches()) {
            errors.put("retryTimeOfDayUtc", "must be HH:mm (00:00 to 23:59)");
            return null;
        }
        return n.textValue();
    }

    private List<Integer> distinctDays(String f, int max) {
        String message = "must be at most " + max + " distinct values between 1 and 60";
        List<Integer> list = intArray(f, max, 1, 60, message);
        if (list != null && new HashSet<>(list).size() != list.size()) {
            errors.put(f, message);
            return null;
        }
        return list;
    }

    private List<Integer> intArray(String f, int maxSize, int min, int max, String message) {
        JsonNode n = body.get(f);
        if (n == null || !n.isArray() || n.size() > maxSize) {
            errors.put(f, message);
            return null;
        }
        List<Integer> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (!item.isIntegralNumber() || !item.canConvertToInt() || item.intValue() < min || item.intValue() > max) {
                errors.put(f, message);
                return null;
            }
            out.add(item.intValue());
        }
        return out;
    }

    private Map<String, Boolean> emailEnabled() {
        String message = "must contain exactly the notification kinds";
        JsonNode n = body.get("emailEnabled");
        if (n == null || !n.isObject() || n.size() != NotificationKind.values().length) {
            errors.put("emailEnabled", message);
            return null;
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (NotificationKind k : NotificationKind.values()) {
            JsonNode v = n.get(k.name());
            if (v == null || !v.isBoolean()) {
                errors.put("emailEnabled", message);
                return null;
            }
            out.put(k.name(), v.booleanValue());
        }
        return out;
    }

    private String format(String f) {
        JsonNode n = body.get(f);
        if (n == null || !n.isTextual() || !NumberFormats.isValid(n.textValue())) {
            errors.put(f, NumberFormats.MESSAGE);
            return null;
        }
        return n.textValue();
    }

    /** Plain single-line text: control / format characters refused on the RAW value, trimmed, blank stored as null. */
    private String text(String f, int maxChars) {
        String message = "must be 1 to " + maxChars + " characters of plain text";
        JsonNode n = body.get(f);
        if (n == null) {
            errors.put(f, "is required (send null for none)");
            return null;
        }
        if (n.isNull()) {
            return null;
        }
        if (!n.isTextual() || !PlainText.Validator.isPlain(n.textValue())) {
            errors.put(f, message);
            return null;
        }
        String stripped = VisibleText.Validator.strip(n.textValue());
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.codePointCount(0, stripped.length()) > maxChars) {
            errors.put(f, message);
            return null;
        }
        return stripped;
    }

    private List<String> channels(String f) {
        String message = f.equals("allowedChannels") ? "must be 1 to 6 distinct channels"
                : "must be a non-empty subset of allowedChannels";
        JsonNode n = body.get(f);
        if (n == null || !n.isArray() || n.isEmpty() || n.size() > 6) {
            errors.put(f, message);
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (!item.isTextual() || !CHANNELS.contains(item.textValue()) || out.contains(item.textValue())) {
                errors.put(f, message);
                return null;
            }
            out.add(item.textValue());
        }
        return out;
    }

    private List<String> ipAllowlist() {
        String message = "must be IP addresses or CIDR blocks";
        JsonNode n = body.get("webhookIpAllowlist");
        if (n == null || !n.isArray() || n.size() > 20) {
            errors.put("webhookIpAllowlist", n != null && n.isArray() ? "must be at most 20 addresses" : message);
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (!item.isTextual() || !isIpOrCidr(item.textValue()) || out.contains(item.textValue())) {
                errors.put("webhookIpAllowlist", message);
                return null;
            }
            out.add(item.textValue());
        }
        return out;
    }

    /** IPv4 or IPv6 literal with an optional /prefix. Never resolves a name: only literal characters reach the JDK. */
    static boolean isIpOrCidr(String s) {
        String addr = s;
        Integer prefix = null;
        int slash = s.indexOf('/');
        if (slash >= 0) {
            addr = s.substring(0, slash);
            String p = s.substring(slash + 1);
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(Character::isDigit)) {
                return false;
            }
            prefix = Integer.parseInt(p);
        }
        java.util.regex.Matcher v4 = IPV4.matcher(addr);
        if (v4.matches()) {
            for (int i = 1; i <= 4; i++) {
                if (Integer.parseInt(v4.group(i)) > 255) {
                    return false;
                }
            }
            return prefix == null || prefix <= 32;
        }
        if (addr.indexOf(':') < 0 || !IPV6_CHARS.matcher(addr).matches()) {
            return false;
        }
        try {
            InetAddress.getByName(addr); // a literal containing ':' is parsed, never looked up
        } catch (UnknownHostException e) {
            return false;
        }
        return prefix == null || prefix <= 128;
    }
}
