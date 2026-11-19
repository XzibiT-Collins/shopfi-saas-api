package com.example.perfume_budget.contract;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** API-06c: numbers the docs state about the admin shop-discount list and the masked webhook URL are the code's constants. */
class Api06DocConstantsContractTest {

    private static final String DOC;

    static {
        try {
            DOC = Files.readString(Path.of("docs/api/checkout.md"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int constant(String className, String field) throws Exception {
        Field f = Class.forName("com.example.perfume_budget.service." + className).getDeclaredField(field);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static int documented(String regex) {
        Matcher m = Pattern.compile(regex).matcher(DOC);
        assertThat(m.find()).as("doc text matching /%s/", regex).isTrue();
        int n = Integer.parseInt(m.group(1));
        assertThat(m.find()).as("exactly one place states it: /%s/", regex).isFalse();
        return n;
    }

    @Test
    void theDocumentedShopDiscountListCapIsTheCodesLimit() throws Exception {
        assertThat(documented("At most (\\d+) rows")).isEqualTo(constant("ShopWideDiscountServiceImpl", "LIST_LIMIT"));
    }

    @Test
    void theDocumentedVisibleTokenTailIsTheCodesMask() throws Exception {
        assertThat(documented("replaced by `\\*\\*\\*\\*` plus its last (\\d+) characters"))
                .isEqualTo(constant("PaymentSettingsServiceImpl", "MASK_VISIBLE"));
    }
}
