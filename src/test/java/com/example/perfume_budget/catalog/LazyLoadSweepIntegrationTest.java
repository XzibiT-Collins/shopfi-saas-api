package com.example.perfume_budget.catalog;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.CartItem;
import com.example.perfume_budget.model.JournalEntry;
import com.example.perfume_budget.model.JournalEntryLine;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.ProductFamily;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.CartRepository;
import com.example.perfume_budget.repository.JournalEntryRepository;
import com.example.perfume_budget.repository.ProductFamilyRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CAT-02: with {@code spring.jpa.open-in-view=false}, a mapper that walks a LAZY association after the repository call
 * returned (no surrounding transaction) throws LazyInitializationException (500). Every route here is exercised with
 * real rows that HAVE the association set (a cart with items, a family with a base unit, products with a unit of
 * measure, a journal entry with lines, a customer with an address), plus the repository contract that the
 * association comes back initialised from the one query (entity graph = join, no per-row lazy load, no N+1).
 * Also: the admin top-products metric with NULL counters (nullable columns, no DB default).
 */
class LazyLoadSweepIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired CartRepository cartRepository;
    @Autowired ProductFamilyRepository familyRepository;
    @Autowired ProductRepository productRepository;
    @Autowired JournalEntryRepository journalEntryRepository;
    @Autowired UserRepository userRepository;

    String tag;

    @BeforeEach
    void tag() {
        tag = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    @AfterEach
    void dropOwnReferenceRows() {
        // the base class truncates products / carts / journal entries AFTER this; first detach what points at the
        // rows this class owns outside those tables (units of measure, categories, ledger accounts)
        jdbc.update("UPDATE tenant_acme.products SET unit_of_measure_id = NULL, category_id = NULL WHERE unit_of_measure_id IN "
                + "(SELECT id FROM tenant_acme.units_of_measure WHERE name LIKE 'Cat02 %') OR category_id IN "
                + "(SELECT id FROM tenant_acme.categories WHERE name LIKE 'CAT02-%')");
        jdbc.update("DELETE FROM tenant_acme.units_of_measure WHERE name LIKE 'Cat02 %'");
        jdbc.update("DELETE FROM tenant_acme.categories WHERE name LIKE 'CAT02-%'");
        jdbc.update("DELETE FROM tenant_acme.journal_entry_lines WHERE account_id IN "
                + "(SELECT id FROM tenant_acme.ledger_accounts WHERE name LIKE 'Cat02 %')");
        jdbc.update("DELETE FROM tenant_acme.ledger_accounts WHERE name LIKE 'Cat02 %'");
    }

    // ------------------------------------------------------------------ helpers

    private String adminToken() {
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
        return tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name")));
    }

    private MvcResult asAdmin(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("X-Tenant-ID", "acme").cookie(new Cookie("accessToken", adminToken()))).andReturn();
    }

    private JsonNode okData(MvcResult r, String what) throws Exception {
        assertThat(r.getResponse().getStatus()).as(what + ": " + r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).get("data");
    }

    private long category() {
        return jdbc.queryForObject("INSERT INTO tenant_acme.categories (name, slug, description, created_at) "
                + "VALUES (?, ?, 'd', now()) RETURNING id", Long.class, "CAT02-" + tag, "cat02-" + tag);
    }

    private long uom(String prefix) {
        return jdbc.queryForObject("INSERT INTO tenant_acme.units_of_measure (code, name) VALUES (?, ?) RETURNING id",
                Long.class, prefix + tag, "Cat02 " + prefix + tag);
    }

    private String sku(long productId) {
        return jdbc.queryForObject("SELECT sku FROM tenant_acme.products WHERE id = ?", String.class, productId);
    }

    private User user(long id) {
        return tenants.runAs(acme, () -> userRepository.findById(id).orElseThrow());
    }

    private String populateBody(long... productIdQtyPairs) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < productIdQtyPairs.length; i += 2) {
            items.add(Map.of("productId", productIdQtyPairs[i], "quantity", productIdQtyPairs[i + 1]));
        }
        return json.writeValueAsString(Map.of("cartItems", items));
    }

    private MvcResult populate(Customer c, String body) throws Exception {
        return mvc.perform(as(post("/api/v1/cart/populate").contentType("application/json").content(body), c)).andReturn();
    }

    private Set<String> names(JsonNode cartItems) {
        Set<String> out = new HashSet<>();
        cartItems.forEach(i -> out.add(i.get("productName").asText()));
        return out;
    }

    // ------------------------------------------------------------------ 1. POST /cart/populate

    @Test
    void populateIntoACartThatAlreadyHasItemsAnswers200WithEveryLine() throws Exception {
        Customer c = customer(acme, "pop1-" + tag + "@acme.test");
        long already = product(acme, "Cat02 Already " + tag, "10.00", 5);
        long added = product(acme, "Cat02 Added " + tag, "7.50", 5);
        jdbc.update("UPDATE tenant_acme.products SET category_id = ? WHERE id IN (?, ?)", category(), already, added);
        cart(c, already, 1);

        JsonNode data = okData(populate(c, populateBody(added, 2)), "populate");

        assertThat(names(data.get("cartItems"))).containsExactlyInAnyOrder("Cat02 Already " + tag, "Cat02 Added " + tag);
        assertThat(data.get("totalPrice").asText()).isEqualTo("GHS 25.00"); // 1 x 10.00 + 2 x 7.50
        for (JsonNode i : data.get("cartItems")) {
            if (i.get("productId").asLong() == added) {
                assertThat(i.get("quantity").asInt()).isEqualTo(2);
                assertThat(i.get("lineTotal").asText()).isEqualTo("GHS 15.00");
            }
        }
    }

    @Test
    void populateForACustomerWithoutACartCreatesItAndAnswers200() throws Exception {
        Customer c = customer(acme, "pop2-" + tag + "@acme.test");
        long p = product(acme, "Cat02 Fresh " + tag, "4.00", 5);

        JsonNode data = okData(populate(c, populateBody(p, 3)), "populate");

        assertThat(data.get("cartItems")).hasSize(1);
        assertThat(data.get("cartItems").get(0).get("productName").asText()).isEqualTo("Cat02 Fresh " + tag);
        assertThat(data.get("totalPrice").asText()).isEqualTo("GHS 12.00");
    }

    @Test
    void populateWithAnEmptyListOnAnEmptyAccountAnswers200WithAnEmptyCart() throws Exception {
        Customer c = customer(acme, "pop3-" + tag + "@acme.test");

        JsonNode data = okData(populate(c, populateBody()), "populate");

        assertThat(data.get("cartItems")).isEmpty();
        assertThat(data.get("totalPrice").asText()).isEqualTo("GHS 0.00");
    }

    @Test
    void populateKeepsThePerItemBehaviour_aBadItemIs207AndTheGoodOneStaysInTheCart() throws Exception {
        Customer c = customer(acme, "pop4-" + tag + "@acme.test");
        long good = product(acme, "Cat02 Good " + tag, "5.00", 5);
        long inactive = product(acme, "Cat02 Inactive " + tag, "5.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET is_active = false WHERE id = ?", inactive);

        MvcResult r = populate(c, populateBody(good, 1, inactive, 1));

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(207);
        assertThat(r.getResponse().getContentAsString()).contains("Cat02 Inactive " + tag);
        // the good line was committed in its own transaction: the failure of the other did not roll it back
        assertThat(jdbc.queryForList("SELECT ci.product_id FROM tenant_acme.cart_items ci JOIN tenant_acme.carts ca "
                + "ON ca.id = ci.cart_id WHERE ca.user_id = ?", Long.class, c.id())).containsExactly(good);
        // and the next populate (all good) maps that cart fine
        JsonNode data = okData(populate(c, populateBody(good, 1)), "second populate");
        assertThat(data.get("cartItems")).hasSize(1);
        assertThat(data.get("cartItems").get(0).get("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void getCartStillMapsEveryLine() throws Exception {
        Customer c = customer(acme, "get1-" + tag + "@acme.test");
        long a = product(acme, "Cat02 GetA " + tag, "3.00", 5);
        long b = product(acme, "Cat02 GetB " + tag, "2.00", 5);
        cart(c, a, 2);
        cart(c, b, 1);

        JsonNode data = okData(mvc.perform(as(get("/api/v1/cart"), c)).andReturn(), "get cart");

        assertThat(names(data.get("cartItems"))).containsExactlyInAnyOrder("Cat02 GetA " + tag, "Cat02 GetB " + tag);
        assertThat(data.get("totalPrice").asText()).isEqualTo("GHS 8.00");
    }

    @Test
    void cartRepositoryLoadsItemsAndTheirProductsInTheSameQuery() {
        Customer c = customer(acme, "repo-" + tag + "@acme.test");
        long a = product(acme, "Cat02 RepoA " + tag, "3.00", 5);
        long b = product(acme, "Cat02 RepoB " + tag, "2.00", 5);
        cart(c, a, 1);
        cart(c, b, 1);
        User u = user(c.id());

        Cart cart = tenants.runAs(acme, () -> cartRepository.findWithItemsByUser(u).orElseThrow());

        assertThat(Hibernate.isInitialized(cart.getItems())).isTrue();
        assertThat(cart.getItems()).hasSize(2);
        for (CartItem item : cart.getItems()) {
            assertThat(Hibernate.isInitialized(item.getProduct())).as("item product").isTrue();
        }
    }

    // ------------------------------------------------------------------ 2./3. product families

    private record Family(long id, String code, long base, long variant, String eaCode, String bxCode) {}

    private Family family() {
        long ea = uom("E");
        long bx = uom("B");
        long base = product(acme, "Cat02 Base " + tag, "20.00", 10);
        long variant = product(acme, "Cat02 Box " + tag, "100.00", 2);
        String code = "FAM-" + tag;
        long fam = jdbc.queryForObject("INSERT INTO tenant_acme.product_families (family_code, name, brand, base_unit_id) "
                + "VALUES (?, ?, 'Brand', ?) RETURNING id", Long.class, code, "Cat02 Family " + tag, base);
        jdbc.update("UPDATE tenant_acme.products SET family_id = ?, unit_of_measure_id = ?, category_id = ?, is_base_unit = true, "
                + "cost_price_amount = 12.50, cost_price_currency = 'GHS' WHERE id = ?", fam, ea, category(), base);
        jdbc.update("UPDATE tenant_acme.products SET family_id = ?, unit_of_measure_id = ?, is_base_unit = false WHERE id = ?",
                fam, bx, variant);
        return new Family(fam, code, base, variant, "E" + tag, "B" + tag);
    }

    @Test
    void adminProductFamiliesListsTheBaseUnitSku() throws Exception {
        Family f = family();
        // a family without a base unit maps too
        jdbc.update("INSERT INTO tenant_acme.product_families (family_code, name) VALUES (?, 'Cat02 Orphan')", "ORPH-" + tag);

        JsonNode data = okData(asAdmin(get("/api/v1/admin/product-families")), "families");

        JsonNode row = null;
        JsonNode orphan = null;
        for (JsonNode n : data) {
            if (n.get("familyCode").asText().equals(f.code())) row = n;
            if (n.get("familyCode").asText().equals("ORPH-" + tag)) orphan = n;
        }
        assertThat(row).as("family row").isNotNull();
        assertThat(row.get("baseUnitProductId").asLong()).isEqualTo(f.base());
        assertThat(row.get("baseUnitSku").asText()).isEqualTo(sku(f.base()));
        assertThat(row.get("name").asText()).isEqualTo("Cat02 Family " + tag);
        assertThat(orphan).as("orphan row").isNotNull();
        assertThat(orphan.get("baseUnitSku").isNull()).isTrue();
    }

    @Test
    void availableUomsListsTakenCodesAndTheBaseUnitCost() throws Exception {
        Family f = family();

        JsonNode data = okData(asAdmin(get("/api/v1/admin/product-families/" + f.id() + "/available-uoms")), "available uoms");

        assertThat(data.get("familyCode").asText()).isEqualTo(f.code());
        assertThat(new BigDecimal(data.get("baseUnitCost").asText())).isEqualByComparingTo("12.50");
        Set<String> taken = new HashSet<>();
        data.get("takenUoms").forEach(n -> taken.add(n.asText()));
        assertThat(taken).containsExactlyInAnyOrder(f.eaCode(), f.bxCode());
        Set<String> available = new HashSet<>();
        data.get("availableUoms").forEach(n -> available.add(n.get("code").asText()));
        assertThat(available).doesNotContain(f.eaCode(), f.bxCode());
    }

    @Test
    void availableUomsOfAnUnknownFamilyIs404() throws Exception {
        MvcResult r = asAdmin(get("/api/v1/admin/product-families/987654321/available-uoms"));
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void familyAndProductRepositoriesLoadBaseUnitAndUnitOfMeasureInTheSameQuery() {
        Family f = family();

        List<ProductFamily> all = tenants.runAs(acme, () -> familyRepository.findAllWithBaseUnit());
        ProductFamily one = tenants.runAs(acme, () -> familyRepository.findWithBaseUnitById(f.id()).orElseThrow());
        List<Product> members = tenants.runAs(acme, () -> productRepository.findByFamily(one));

        ProductFamily listed = all.stream().filter(x -> x.getId() == f.id()).findFirst().orElseThrow();
        assertThat(Hibernate.isInitialized(listed.getBaseUnit())).as("listed base unit").isTrue();
        assertThat(Hibernate.isInitialized(one.getBaseUnit())).as("single base unit").isTrue();
        assertThat(members).hasSize(2);
        for (Product p : members) {
            assertThat(Hibernate.isInitialized(p.getUnitOfMeasure())).as("unit of measure of " + p.getId()).isTrue();
        }
    }

    // ------------------------------------------------------------------ (d) top products with NULL counters

    @Test
    void topProductsAnswers200WhenTheCountersAreNull() throws Exception {
        long nulls = product(acme, "Cat02 Nulls " + tag, "5.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET add_to_cart_count = NULL, view_count = NULL, sold_count = 900000 WHERE id = ?", nulls);
        long counted = product(acme, "Cat02 Counted " + tag, "5.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET add_to_cart_count = 4, view_count = 9, sold_count = 899999 WHERE id = ?", counted);
        long noSales = product(acme, "Cat02 NoSales " + tag, "5.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET add_to_cart_count = 3, view_count = NULL, sold_count = NULL WHERE id = ?", noSales);

        JsonNode data = okData(asAdmin(get("/api/v1/admin/metrics/top-products")), "top products");

        JsonNode n = null;
        JsonNode cnt = null;
        JsonNode ns = null;
        for (JsonNode row : data) {
            long id = row.get("productId").asLong();
            if (id == nulls) n = row;
            if (id == counted) cnt = row;
            if (id == noSales) ns = row;
        }
        assertThat(n).as("null-counter row").isNotNull();
        assertThat(n.get("addToCartCount").asLong()).isZero();
        assertThat(n.get("viewCount").asLong()).isZero();
        assertThat(n.get("soldCount").asLong()).isEqualTo(900000);
        assertThat(new BigDecimal(n.get("conversionRate").asText())).isEqualByComparingTo("0");
        assertThat(cnt).as("counted row").isNotNull();
        assertThat(cnt.get("addToCartCount").asLong()).isEqualTo(4);
        assertThat(cnt.get("viewCount").asLong()).isEqualTo(9);
        // sold > carted is possible (walk-in sales): the rate is reported, not capped
        assertThat(new BigDecimal(cnt.get("conversionRate").asText())).isEqualByComparingTo(
                BigDecimal.valueOf(899999.0 / 4 * 100).setScale(2, java.math.RoundingMode.HALF_UP));
        // NULL sold_count sorts as 0 (not first under DESC NULLS FIRST) and maps to 0
        if (ns != null) {
            assertThat(ns.get("soldCount").asLong()).isZero();
            assertThat(new BigDecimal(ns.get("conversionRate").asText())).isEqualByComparingTo("0");
        }
        assertThat(data.get(0).get("productId").asLong()).as("highest sold first").isEqualTo(nulls);
    }

    @Test
    void nullSoldCountDoesNotSortFirst() throws Exception {
        long sold = product(acme, "Cat02 Sold " + tag, "5.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET sold_count = 7 WHERE id = ?", sold);
        for (int i = 0; i < 6; i++) {
            long p = product(acme, "Cat02 NullSold " + i + " " + tag, "5.00", 5);
            jdbc.update("UPDATE tenant_acme.products SET sold_count = NULL WHERE id = ?", p);
        }

        JsonNode data = okData(asAdmin(get("/api/v1/admin/metrics/top-products")), "top products");

        assertThat(data.get(0).get("productId").asLong()).isEqualTo(sold);
        assertThat(data.get(0).get("soldCount").asLong()).isEqualTo(7);
    }

    @Test
    void theCounterIncrementsCountFromNullInsteadOfStayingNull() {
        long p = product(acme, "Cat02 Inc " + tag, "5.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET add_to_cart_count = NULL, view_count = NULL, sold_count = NULL WHERE id = ?", p);

        tenants.runAs(acme, () -> {
            productRepository.incrementCartTimesCount(p);
            productRepository.incrementViewCount(p);
            productRepository.incrementSoldCount(p, 3);
            return null;
        });

        Map<String, Object> row = jdbc.queryForMap("SELECT add_to_cart_count, view_count, sold_count FROM tenant_acme.products WHERE id = ?", p);
        assertThat(((Number) row.get("add_to_cart_count")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("view_count")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("sold_count")).longValue()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ sweep: other lazy reads found by CAT-02

    private long journalEntry(boolean manual, String referenceId) {
        long account = jdbc.queryForObject("INSERT INTO tenant_acme.ledger_accounts (code, name, type, category, is_active, balance) "
                + "VALUES (?, ?, 'ASSET', 'CASH', true, 0) RETURNING id", Long.class, "C2-" + tag + referenceId, "Cat02 Cash " + tag + referenceId);
        long entry = jdbc.queryForObject("INSERT INTO tenant_acme.journal_entries (entry_number, description, type, reference_type, "
                + "reference_id, is_manual, transaction_date, created_at) VALUES (?, 'Cat02 entry', 'SALE', 'ORDER', ?, ?, now(), now()) RETURNING id",
                Long.class, "JE-" + tag + referenceId, referenceId, manual);
        jdbc.update("INSERT INTO tenant_acme.journal_entry_lines (journal_entry_id, account_id, entry_type, amount, description) "
                + "VALUES (?, ?, 'DEBIT', 10.00, 'cat02 line'), (?, ?, 'CREDIT', 10.00, 'cat02 line')", entry, account, entry, account);
        return entry;
    }

    @Test
    void journalEntryRoutesMapLinesAndTheirAccounts() throws Exception {
        journalEntry(true, "R1" + tag);
        String today = LocalDate.now().toString();

        List<JsonNode> results = List.of(
                okData(asAdmin(get("/api/v1/admin/accounting/journal-entries").param("startDate", LocalDate.now().minusDays(1).toString())
                        .param("endDate", LocalDate.now().plusDays(1).toString())), "journal entries " + today).get("content"), // CAT-03: paged
                okData(asAdmin(get("/api/v1/admin/accounting/journal-entries/reference").param("referenceType", "ORDER")
                        .param("referenceId", "R1" + tag)), "by reference"),
                okData(asAdmin(get("/api/v1/admin/accounting/journal-entries/manual")), "manual"));

        for (JsonNode data : results) {
            JsonNode mine = null;
            for (JsonNode e : data) {
                if (e.get("entryNumber").asText().equals("JE-" + tag + "R1" + tag)) mine = e;
            }
            assertThat(mine).as("entry in " + data).isNotNull();
            assertThat(mine.get("lines")).hasSize(2);
            for (JsonNode line : mine.get("lines")) {
                assertThat(line.get("accountName").asText()).isEqualTo("Cat02 Cash " + tag + "R1" + tag);
            }
        }
    }

    @Test
    void journalEntryRepositoryLoadsLinesAndAccountsInTheSameQuery() {
        journalEntry(true, "R2" + tag);
        journalEntry(true, "R3" + tag);

        List<List<JournalEntry>> reads = tenants.runAs(acme, () -> List.of(
                journalEntryRepository.findWithLinesByIdIn(journalEntryRepository.findIdPageByDateRange(LocalDate.now().minusDays(1).atStartOfDay(),
                        LocalDate.now().plusDays(1).atStartOfDay(), org.springframework.data.domain.PageRequest.of(0, 50)).getContent()),
                journalEntryRepository.findByReferenceTypeAndReferenceId("ORDER", "R2" + tag),
                journalEntryRepository.findByIsManualTrueOrderByTransactionDateDesc()));

        for (List<JournalEntry> read : reads) {
            assertThat(read).isNotEmpty();
            for (JournalEntry e : read) {
                assertThat(Hibernate.isInitialized(e.getLines())).as("lines of " + e.getEntryNumber()).isTrue();
                for (JournalEntryLine l : e.getLines()) {
                    assertThat(Hibernate.isInitialized(l.getAccount())).as("account").isTrue();
                }
            }
        }
        // the join does not duplicate the entries (one row per entry, not per line)
        assertThat(reads.get(2).stream().map(JournalEntry::getId).distinct().count()).isEqualTo(reads.get(2).size());
        assertThat(reads.get(1)).hasSize(1);
    }

    @Test
    void adminCustomerDetailsMapsTheDeliveryAddresses() throws Exception {
        Customer c = customer(acme, "cd-" + tag + "@acme.test");
        long extra = extraAddress(c);

        JsonNode data = okData(asAdmin(get("/api/v1/admin/metrics/customers/" + c.id())), "customer details");

        assertThat(data.get("email").asText()).isEqualTo(c.email());
        assertThat(data.get("addresses")).hasSize(2);
        assertThat(extra).isPositive();
    }

    @Test
    void userRepositoryLoadsDeliveryAddressesInTheSameQuery() {
        Customer c = customer(acme, "cdr-" + tag + "@acme.test");

        User u = tenants.runAs(acme, () -> userRepository.findWithDeliveryAddressesById(c.id()).orElseThrow());

        assertThat(Hibernate.isInitialized(u.getDeliveryAddresses())).isTrue();
        assertThat(u.getDeliveryAddresses()).hasSize(1);
    }
}
