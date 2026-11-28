package com.example.perfume_budget.contract;

import com.example.perfume_budget.dto.tax.TaxCalculationResult;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderItemRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInQuoteRequest;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.DiscountSource;
import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.CouponRejectedException;
import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.CartItem;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.ShopWideDiscount;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.CouponRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.repository.WalkInCustomerRepository;
import com.example.perfume_budget.repository.WalkInOrderAuditEventRepository;
import com.example.perfume_budget.repository.WalkInOrderRepository;
import com.example.perfume_budget.repository.ShopWideDiscountRepository;
import com.example.perfume_budget.service.CheckoutPricingService;
import com.example.perfume_budget.service.CouponPerUserLimit;
import com.example.perfume_budget.service.EffectivePriceService;
import com.example.perfume_budget.service.BookkeepingService;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.service.interfaces.TaxService;
import com.example.perfume_budget.utils.WalkInOrderNumberGenerator;
import com.example.perfume_budget.service.WalkInOrderServiceImpl;
import com.example.perfume_budget.service.interfaces.FrontDeskAccessService;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.DiscountCalculationUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * API-RD-09 (discount part): docs/api/discount-stacking.md. Every row of its pricing table is evaluated by the real
 * EffectivePriceService; the "no coupon / no manual discount next to an automatic discount" rule is run through the real
 * CheckoutPricingService and WalkInOrderServiceImpl (store-wide sale included).
 */
class DiscountStackingDocContractTest {

    private static final Path DOC = Path.of("docs/api/discount-stacking.md");
    private static final String SRC = "src/main/java/com/example/perfume_budget/";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 23, 12, 0);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ShopWideDiscountRepository shopRepo = mock(ShopWideDiscountRepository.class);
    private final EffectivePriceService prices = new EffectivePriceService(shopRepo,
            Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

    private static Product product(String price) {
        return Product.builder().id(1L).name("P").sku("S").isActive(true).stockQuantity(10)
                .price(new Money(new BigDecimal(price), CurrencyCode.GHS)).build();
    }

    private static ShopWideDiscount shop(String pct) {
        return ShopWideDiscount.builder().label("sale").discountPercentage(new BigDecimal(pct))
                .startAt(NOW.minusDays(1)).endAt(NOW.plusDays(1)).isActive(true).build();
    }

    private static JsonNode cases() throws Exception {
        Matcher m = Pattern.compile("```json pricing-cases\\n(.*?)```", Pattern.DOTALL).matcher(Files.readString(DOC));
        assertThat(m.find()).isTrue();
        return JSON.readTree(m.group(1));
    }

    @Test
    void everyRowOfThePricingTableIsWhatTheCodeComputes() throws Exception {
        JsonNode rows = cases();
        assertThat(rows.size()).isEqualTo(10);
        for (JsonNode row : rows) {
            Product p = product(row.get("price").asText());
            JsonNode d = row.get("product");
            if (!d.isNull()) {
                LocalDateTime start = NOW.minusHours(1);
                LocalDateTime end = NOW.plusHours(1);
                String window = d.has("window") ? d.get("window").asText() : "";
                if (window.equals("ENDED")) { start = NOW.minusDays(3); end = NOW.minusDays(2); }
                if (window.equals("FUTURE")) { start = NOW.plusDays(2); end = NOW.plusDays(3); }
                p.setDiscountType(DiscountType.valueOf(d.get("type").asText()));
                p.setDiscountValue(new BigDecimal(d.get("value").asText()));
                p.setDiscountStartAt(start);
                p.setDiscountEndAt(end);
            }
            ShopWideDiscount s = row.get("shop").isNull() ? null : shop(row.get("shop").asText());

            EffectivePrice r = prices.compute(p, s, NOW);

            String name = row.get("case").asText();
            assertThat(r.effectiveAmount()).as(name).isEqualByComparingTo(row.get("effective").asText());
            assertThat(r.onSale()).as(name).isEqualTo(row.get("onSale").asBoolean());
            assertThat(r.source()).as(name).isEqualTo(DiscountSource.valueOf(row.get("source").asText()));
        }
    }

    @Test
    void windowEdgesAreInclusiveAsDocumented() {
        Product p = product("100.00");
        p.setDiscountType(DiscountType.FLAT);
        p.setDiscountValue(new BigDecimal("10"));
        p.setDiscountStartAt(NOW);
        p.setDiscountEndAt(NOW);
        assertThat(prices.compute(p, shop("50"), NOW).source()).isEqualTo(DiscountSource.PRODUCT);
        assertThat(prices.compute(p, shop("50"), NOW.plusSeconds(1)).source()).isEqualTo(DiscountSource.SHOP);
        assertThat(prices.compute(p, shop("50"), NOW.minusSeconds(1)).source()).isEqualTo(DiscountSource.SHOP);
    }

    // ------------------------------------------------------------------ checkout / preview: coupon

    private CheckoutPricingService checkoutPricing(ShopWideDiscount active, Coupon coupon) {
        when(shopRepo.findActiveNow(any())).thenReturn(Optional.ofNullable(active));
        CouponRepository couponRepo = mock(CouponRepository.class);
        when(couponRepo.findByCode(any())).thenReturn(Optional.ofNullable(coupon));
        DiscountCalculationUtil util = mock(DiscountCalculationUtil.class);
        when(util.calculateDiscount(any(), any())).thenReturn(new BigDecimal("10.00"));
        TaxService tax = mock(TaxService.class);
        when(tax.calculateTaxes(any())).thenReturn(TaxCalculationResult.builder().orderTaxes(List.of())
                .totalTaxAmount(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                .totalAmountAfterTax(new Money(BigDecimal.ZERO, CurrencyCode.GHS)).build());
        return new CheckoutPricingService(prices, couponRepo, mock(CouponPerUserLimit.class), util, tax);
    }

    private static Cart cartOf(Product p) {
        CartItem item = new CartItem();
        item.setProduct(p);
        item.setQuantity(1);
        Cart cart = new Cart();
        List<CartItem> items = new ArrayList<>();
        items.add(item);
        cart.setItems(items);
        return cart;
    }

    @Test
    void couponIsRefusedWhenAnyLineIsOnSaleFromEitherSource() {
        Product plain = product("100.00");
        Product withOwnSale = product("100.00");
        withOwnSale.setDiscountType(DiscountType.PERCENTAGE);
        withOwnSale.setDiscountValue(new BigDecimal("10"));
        withOwnSale.setDiscountStartAt(NOW.minusHours(1));
        withOwnSale.setDiscountEndAt(NOW.plusHours(1));

        // product sale
        assertThatThrownBy(() -> checkoutPricing(null, mock(Coupon.class)).quote(cartOf(withOwnSale), "SAVE"))
                .isInstanceOfSatisfying(CouponRejectedException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(CouponRejectedException.Reason.WITH_SALE_ITEMS);
                    assertThat(e.getMessage()).isEqualTo("Coupons cannot be combined with active product discounts.");
                });
        // store-wide sale makes every priced product "on sale": the coupon is refused for a plain product too
        assertThatThrownBy(() -> checkoutPricing(shop("20"), mock(Coupon.class)).quote(cartOf(plain), "SAVE"))
                .isInstanceOfSatisfying(CouponRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(CouponRejectedException.Reason.WITH_SALE_ITEMS));
        // control: no sale at all, the same coupon applies on the full price
        CheckoutPricingService.Quote q = checkoutPricing(null, mock(Coupon.class)).quote(cartOf(plain), "SAVE");
        assertThat(q.couponDiscount()).isEqualByComparingTo("10.00");
        assertThat(q.automaticDiscount()).isEqualByComparingTo("0");
        // and the automatic discount alone is fine without a coupon
        CheckoutPricingService.Quote noCoupon = checkoutPricing(shop("20"), null).quote(cartOf(plain), null);
        assertThat(noCoupon.automaticDiscount()).isEqualByComparingTo("20.00");
        assertThat(noCoupon.orderSubtotal()).isEqualByComparingTo("80.00");
    }

    // ------------------------------------------------------------------ walk-in: manual discount

    @Test
    void walkInManualDiscountIsRefusedWhenALineIsOnSale() {
        ProductRepository productRepo = mock(ProductRepository.class);
        AuthUserUtil auth = mock(AuthUserUtil.class);
        TaxService tax = mock(TaxService.class);
        EffectivePriceService eff = mock(EffectivePriceService.class);
        FrontDeskAccessService access = mock(FrontDeskAccessService.class);
        User admin = User.builder().id(10L).roles(UserRole.ADMIN).build();
        when(auth.getCurrentUser()).thenReturn(admin);
        Product p = product("100.00");
        when(productRepo.findById(1L)).thenReturn(Optional.of(p));
        when(eff.activeShopDiscount()).thenReturn(Optional.empty());
        when(eff.now()).thenReturn(NOW);
        when(tax.calculateTaxes(any())).thenAnswer(inv -> {
            BigDecimal base = inv.getArgument(0);
            return TaxCalculationResult.builder().orderTaxes(List.of())
                    .totalTaxAmount(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                    .totalAmountAfterTax(new Money(base, CurrencyCode.GHS)).build();
        });
        WalkInOrderServiceImpl svc = walkIn(productRepo, auth, tax, eff, access);
        WalkInQuoteRequest withManual = new WalkInQuoteRequest(List.of(new WalkInOrderItemRequest(1L, 1)),
                DiscountType.PERCENTAGE, new BigDecimal("10"), null, null);

        // on sale (either source, the service only sees onSale): refused with the documented text
        when(eff.compute(any(Product.class), any(), any())).thenReturn(new EffectivePrice(new BigDecimal("100.00"),
                new BigDecimal("80.00"), CurrencyCode.GHS, true, DiscountSource.SHOP, DiscountType.PERCENTAGE, new BigDecimal("20.00"), null));
        assertThatThrownBy(() -> svc.quote(withManual)).isInstanceOf(BadRequestException.class)
                .hasMessage("Manual discount cannot be combined with active product discounts.");
        // the automatic discount alone is fine
        assertThat(svc.quote(new WalkInQuoteRequest(List.of(new WalkInOrderItemRequest(1L, 1)), null, null, null, null))
                .automaticDiscountAmount()).contains("20.00");

        // control: nothing on sale, the manual discount applies
        when(eff.compute(any(Product.class), any(), any())).thenReturn(new EffectivePrice(new BigDecimal("100.00"),
                new BigDecimal("100.00"), CurrencyCode.GHS, false, DiscountSource.NONE, null, BigDecimal.ZERO, null));
        assertThat(svc.quote(withManual).discountAmount()).contains("10.00");
    }

    private static WalkInOrderServiceImpl walkIn(ProductRepository productRepo, AuthUserUtil auth, TaxService tax,
                                                 EffectivePriceService eff, FrontDeskAccessService access) {
        return new WalkInOrderServiceImpl(mock(com.example.perfume_budget.entitlement.EntitlementService.class), mock(WalkInOrderRepository.class), mock(WalkInOrderAuditEventRepository.class),
                mock(WalkInCustomerRepository.class), mock(UserRepository.class), productRepo, tax, auth,
                mock(WalkInOrderNumberGenerator.class), mock(BookkeepingService.class), mock(InventoryManagementService.class),
                access, eff);
    }

    // ------------------------------------------------------------------ the prose

    @Test
    void theProseNamesWhatTheCodeDoes() throws Exception {
        String doc = Files.readString(DOC);
        String eff = Files.readString(Path.of(SRC + "service/EffectivePriceService.java"));
        // product first, store-wide second (code order is what the doc's numbered list says)
        assertThat(eff.indexOf("isProductDiscountActive(product, now)")).isLessThan(eff.indexOf("activeShop != null"));
        assertThat(eff).contains("DiscountType.PERCENTAGE, activeShop.getDiscountPercentage()");
        // rounding and bounds the prose states
        String util = Files.readString(Path.of(SRC + "utils/DiscountCalculationUtil.java"));
        assertThat(util).contains("RoundingMode.HALF_UP");
        assertThat(eff).contains("RoundingMode.HALF_EVEN");
        assertThat(doc).contains("percent discounts `HALF_UP`, the resulting price `HALF_EVEN`")
                .contains("(`discountPercentage`, 0 < p <= 100)");
        assertThat(doc).contains("percentage only").contains("**not** \"the larger of the two\"");
        // store-wide request carries only a percentage (no type), in (0, 100]
        String req = Files.readString(Path.of(SRC + "dto/discount/request/ShopWideDiscountRequest.java"));
        assertThat(req).contains("discountPercentage").doesNotContain("DiscountType").contains("@DecimalMax(value = \"100.0\"");
        // refusal texts and the preview reason the doc quotes
        assertThat(Files.readString(Path.of(SRC + "service/CheckoutPricingService.java")))
                .contains("\"Coupons cannot be combined with active product discounts.\"");
        assertThat(Files.readString(Path.of(SRC + "service/CartPreviewService.java"))).contains("\"COUPON_NOT_COMBINABLE\"");
        assertThat(doc).contains("Coupons cannot be combined with active product discounts.").contains("COUPON_NOT_COMBINABLE")
                .contains("Manual discount cannot be combined with active product discounts.");
        // /tax?couponCode= is a calculator that never looks at sale items
        String taxSvc = Files.readString(Path.of(SRC + "service/TaxServiceImpl.java"));
        String calc = taxSvc.substring(taxSvc.indexOf("calculateTaxesAtCheckout"));
        assertThat(calc).doesNotContain("onSale").doesNotContain("EffectivePrice");
        assertThat(doc).contains("only a calculator over a number the caller sends");
        // the DiscountSource is not exposed in any response record
        try (var files = Files.walk(Path.of(SRC + "dto"))) {
            String dtos = files.filter(f -> f.toString().endsWith(".java")).map(f -> {
                try { return Files.readString(f); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            }).reduce("", String::concat);
            assertThat(dtos).doesNotContain("DiscountSource");
        }
    }

    /** Product decision 2026-11-23: no coupon when any item is on discount (either source); the product's own discount wins. */
    @Test
    void decisionsOfTheProductOwnerArePinned() throws Exception {
        String doc = Files.readString(DOC);
        assertThat(doc).contains("**a coupon is not allowed when any item in the cart is on\ndiscount**")
                .contains("the\nproduct's own discount wins over the store-wide one");
        Product own = product("100.00");
        own.setDiscountType(DiscountType.PERCENTAGE);
        own.setDiscountValue(new BigDecimal("10"));
        own.setDiscountStartAt(NOW.minusHours(1));
        own.setDiscountEndAt(NOW.plusHours(1));
        // product wins over store-wide (90.00, not 80.00) ...
        assertThat(prices.compute(own, shop("20"), NOW).effectiveAmount()).isEqualByComparingTo("90.00");
        assertThat(prices.compute(own, shop("20"), NOW).source()).isEqualTo(DiscountSource.PRODUCT);
        // ... and in that same situation (product discount AND store-wide sale) a coupon is still refused
        assertThatThrownBy(() -> checkoutPricing(shop("20"), mock(Coupon.class)).quote(cartOf(own), "SAVE"))
                .isInstanceOfSatisfying(CouponRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(CouponRejectedException.Reason.WITH_SALE_ITEMS));
    }
}
