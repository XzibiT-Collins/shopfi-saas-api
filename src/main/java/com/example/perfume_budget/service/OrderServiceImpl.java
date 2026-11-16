package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.dto.order.OrderStatusUpdateRequest;
import com.example.perfume_budget.dto.checkout.CheckoutResponse;
import com.example.perfume_budget.dto.payment.request.PaystackInitiatePaymentRequest;
import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;
import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.PaymentException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.mapper.OrderItemMapper;
import com.example.perfume_budget.mapper.OrderMapper;
import com.example.perfume_budget.model.*;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.*;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.service.interfaces.OrderService;
import com.example.perfume_budget.service.interfaces.PaymentGatewayAPIService;
import com.example.perfume_budget.utils.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final OrderRepository orderRepository;
    private final AuthUserUtil authUserUtil;
    private final CartUtil cartUtil;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;
    private final OrderNumberGenerator orderNumberGenerator;
    private final PaymentReferenceGenerator paymentReferenceGenerator;
    private final PaymentGatewayAPIService gatewayAPIService;
    private final DeliveryDetailRepository deliveryDetailRepository;
    private final CheckoutPricingService pricingService;
    private final CheckoutCallbackUrlProvider callbackUrlProvider;
    private final InventoryManagementService inventoryManagementService;
    private final DiscountCalculationUtil discountCalculationUtil;
    private final OrderLifecycleService lifecycleService;
    private final EffectivePriceService effectivePriceService;
    private final CouponRedemptionService couponRedemptionService;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final TransientRetry transientRetry;
    /** Orders are sortable only by these (a free-form sort path would be a column/oracle probe: user.password...). */
    public static final java.util.Set<String> ORDER_SORT_PROPERTIES =
            java.util.Set.of("createdAt", "orderNumber", "status", "deliveryStatus", "id");

    @Value("${inventory.reservation.expiry-hours:1}")
    private long expiryHours;

    private static final String ORDER_NOT_FOUND = "Order not found.";
    private static final String EMPTY_CART = "Cart is empty, add items and try again.";
    private static final String PRODUCT_UNAVAILABLE = "One or more products in your cart are no longer available for ecommerce purchase.";


    /**
     * Checkout. API-04 rework: NOT one transaction any more, so that no coupon row lock is held across the Paystack
     * call.
     * <ol>
     *   <li>(rework 2, coupon checkouts only) a short pre-check answers everything that can be decided without the
     *       usage counter BEFORE a redemption is reserved: delivery address, empty cart, unavailable items, idempotent
     *       replays (keyed and fingerprint), sale items, unknown code, the per-user limit and the minimum amount. A
     *       request refused or replayed there never touches the counter, so it cannot make another customer's
     *       checkout see a transiently full coupon;</li>
     *   <li>a coupon redemption is reserved by an atomic conditional UPDATE in its own short transaction that has
     *       committed before anything else starts ({@link CouponRedemptionService#reserve}). Active / date window /
     *       usage limit are decided ONLY there;</li>
     *   <li>the checkout itself runs in one transaction (customer row lock, replay checks, pricing, order, an
     *       unlocked stock pre-check, Paystack call, then (API-05) the stock reservation as the last step: it takes the
     *       product row locks in id order only for that short step, see {@link ProductStockLocks}). Not retried
     *       in-process: a retry would initialise a second Paystack transaction. If the pre-check skipped
     *       the reservation because it saw a replay that is gone by now, this transaction stops before pricing and
     *       the checkout runs once more with a reservation;</li>
     *   <li>unless that transaction committed a new order holding the reservation, the reservation is given back
     *       (replay, rejection, Paystack failure, any error).</li>
     * </ol>
     */
    @Override
    public CheckoutResponse checkout(String couponCode, Long deliveryDetailId, String idempotencyKey) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        boolean hasCoupon = couponCode != null && !couponCode.isBlank();
        boolean reserve = hasCoupon && Boolean.TRUE.equals(new TransactionTemplate(transactionManager)
                .execute(status -> needsReservation(couponCode, deliveryDetailId, key)));
        while (true) {
            CouponRedemptionService.Reservation reservation = reserve ? couponRedemptionService.reserve(couponCode) : null;
            boolean consumed = false;
            try {
                CheckoutOutcome outcome = new TransactionTemplate(transactionManager)
                        .execute(status -> checkoutInTransaction(couponCode, deliveryDetailId, key, hasCoupon, reservation));
                // only reached after the commit: a commit failure leaves consumed=false and the reservation is released
                consumed = outcome.consumedReservation();
                return outcome.response();
            } catch (ReservationNeeded e) {
                if (reserve) {
                    throw new IllegalStateException("Checkout asked for a reservation it already had");
                }
                reserve = true; // the replay the pre-check saw is gone: run once more, with a reservation
            } finally {
                if (reservation != null && !consumed) {
                    couponRedemptionService.releaseReservation(reservation);
                }
            }
        }
    }

    /** The checkout transaction's answer and whether it committed an order holding the coupon reservation. */
    private record CheckoutOutcome(CheckoutResponse response, boolean consumedReservation) {
        static CheckoutOutcome replayed(CheckoutResponse response) {
            return new CheckoutOutcome(response, false);
        }
    }

    /** The checkout transaction reached pricing with a coupon but without a reservation (rolled back, retried). */
    private static final class ReservationNeeded extends RuntimeException {
        ReservationNeeded() {
            super(null, null, false, false);
        }
    }

    /**
     * Pre-check of a coupon checkout (no locks, nothing written that survives a refusal). Throws the same refusal
     * the checkout transaction would throw for everything that does not depend on the usage counter; returns false
     * when the checkout will be answered by an idempotent replay (no reservation needed), true otherwise.
     */
    private boolean needsReservation(String couponCode, Long deliveryDetailId, String key) {
        User user = authUserUtil.getCurrentUser();
        DeliveryDetail delivery = resolveDeliveryDetail(user, deliveryDetailId);
        Cart cart = cartUtil.getCurrentUserCart();
        if (key != null && orderRepository.findByUserAndIdempotencyKey(user, key).isPresent()) {
            return false; // answered by the keyed replay (or its 409 / 422) in the checkout transaction
        }
        if (cart.getItems().isEmpty()) {
            throw new BadRequestException(EMPTY_CART);
        }
        validateCartItemsForEcommerce(cart);
        if (key == null) {
            String fingerprint = CheckoutFingerprint.of(cart, CheckoutFingerprint.normalizeCoupon(couponCode), delivery.getId());
            LocalDateTime openCutoff = LocalDateTime.now().minusHours(expiryHours);
            for (Order open : orderRepository.findOpenByFingerprint(user, fingerprint, openCutoff)) {
                if (replay(open).isPresent()) {
                    return false;
                }
            }
        }
        pricingService.precheckCoupon(cart, couponCode);
        return true;
    }

    private CheckoutOutcome checkoutInTransaction(String couponCode, Long deliveryDetailId, String key, boolean hasCoupon,
                                                  CouponRedemptionService.Reservation reservation) {
        User principal = authUserUtil.getCurrentUser();
        // Serialise this customer's checkouts in this tenant (double submit, two tabs, a retry racing the original):
        // the second request waits here and then finds the first request's order instead of creating another.
        User currentUser = userRepository.lockById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        DeliveryDetail delivery = resolveDeliveryDetail(currentUser, deliveryDetailId);
        Cart cart = cartUtil.getCurrentUserCart();
        LocalDateTime openCutoff = LocalDateTime.now().minusHours(expiryHours);

        if (key != null) {
            Optional<Order> existing = orderRepository.findByUserAndIdempotencyKey(currentUser, key);
            if (existing.isPresent()) {
                return CheckoutOutcome.replayed(replayKeyed(existing.get(), cart, couponCode, delivery, openCutoff));
            }
        }

        if (cart.getItems().isEmpty()) {
            throw new BadRequestException(EMPTY_CART);
        }
        validateCartItemsForEcommerce(cart);

        String couponNorm = CheckoutFingerprint.normalizeCoupon(couponCode);
        String fingerprint = CheckoutFingerprint.of(cart, couponNorm, delivery.getId());
        if (key == null) {
            // No key: the customer's still-open order for the very same cart/coupon/address is the answer.
            for (Order open : orderRepository.findOpenByFingerprint(currentUser, fingerprint, openCutoff)) {
                Optional<CheckoutResponse> replay = replay(open);
                if (replay.isPresent()) {
                    return CheckoutOutcome.replayed(replay.get());
                }
            }
        }

        if (hasCoupon && reservation == null) {
            throw new ReservationNeeded();
        }
        // The usage counter was already advanced by the reservation; nothing here writes the coupon row.
        CheckoutPricingService.Quote quote = pricingService.quoteReserved(cart, reservation);
        Coupon coupon = quote.coupon();

        Order newOrder = Order.builder()
                .orderNumber(orderNumberGenerator.generateOrderNumber())
                .user(currentUser)
                .subtotal(new Money(quote.orderSubtotal(), CurrencyCode.GHS))
                .discountAmount(new Money(quote.couponDiscount(), CurrencyCode.GHS))
                .automaticDiscountAmount(new Money(quote.automaticDiscount(), CurrencyCode.GHS))
                .totalTaxAmount(quote.taxes().totalTaxAmount())
                .totalAmount(quote.taxes().totalAmountAfterTax())
                .status(PaymentStatus.PENDING)
                .deliveryStatus(OrderProcessingStatus.PENDING)
                .coupon(coupon)
                .idempotencyKey(key)
                .requestFingerprint(fingerprint)
                .deliveryDetail(delivery)
                .build();

        newOrder.setItems(createOrderItems(cart, newOrder, effectivePriceService.activeShopDiscount().orElse(null),
                effectivePriceService.now()));
        Order finalNewOrder = newOrder;
        quote.taxes().orderTaxes().forEach(tax -> tax.setOrder(finalNewOrder));
        newOrder.setTaxes(new ArrayList<>(quote.taxes().orderTaxes()));
        newOrder = orderRepository.save(newOrder);

        // Save payment as PENDING before any API call
        Payment newPayment = createPaymentObject(newOrder);
        newPayment.setCustomerEmail(currentUser.getEmail());
        paymentRepository.save(newPayment);

        // API-05: a cart this shop cannot serve is refused before Paystack is asked (plain read, no lock). The binding
        // decision is the locked reservation below; losing a race there gives the same 400.
        requireStockLooksAvailable(cart);

        PaystackInitiateTransactionResponse response;
        try {
            PaystackInitiatePaymentRequest request = PaystackInitiatePaymentRequest.builder()
                    .email(currentUser.getEmail())
                    .amount(convertAmountToSend(newOrder.getTotalAmount().getAmount()))
                    .currency(newOrder.getTotalAmount().getCurrencyCode())
                    .callbackUrl(callbackUrlProvider.callbackUrl().orElse(null))
                    .build();

            response = gatewayAPIService.initiatePaystackTransaction(request);
            log.info("Payment initiated for order: {}", newOrder.getOrderNumber());
        } catch (PaymentException exception) {
            // nothing is reserved yet; the order / payment below roll back with this transaction anyway
            newOrder.setStatus(PaymentStatus.FAILED);
            orderRepository.save(newOrder);

            newPayment.setStatus(PaymentStatus.FAILED);
            newPayment.setFailureReason(exception.getMessage());
            paymentRepository.save(newPayment);

            log.error("Payment initiation failed for order {}: {}", newOrder.getOrderNumber(), exception.getMessage());
            throw exception;
        }

        // API-05: the stock is reserved AFTER the Paystack call, as the last step before commit. Reserving takes the
        // products' row locks (id order, ProductStockLocks) and they are held until this commit, so concurrent
        // checkouts of the same product wait only for each other's short reservation, never for a Paystack round
        // trip, and none of them fails on Product.@Version. Out of stock here -> 400, the whole transaction (order,
        // payment) rolls back and the initialised Paystack transaction is never shown to anyone.
        reserveStock(newOrder.getOrderNumber(), newOrder.getItems());

        newPayment.setProviderPaymentReference(response.data().reference());
        newPayment.setAuthorizationUrl(response.data().authorizationUrl());
        newPayment.setAccessCode(response.data().accessCode());
        newPayment.setStatus(PaymentStatus.INITIATED);
        paymentRepository.save(newPayment);

        newOrder.setStatus(PaymentStatus.INITIATED);
        eventPublisher.publishEvent(orderRepository.save(newOrder));
        return new CheckoutOutcome(
                toCheckoutResponse(newOrder, response.status(), response.message(), response.data(), false),
                coupon != null);
    }

    /** Pre-check only (no lock): the stock the product showed when this transaction read it. */
    private void requireStockLooksAvailable(Cart cart) {
        for (CartItem item : cart.getItems()) {
            Product product = item.getProduct();
            Integer stock = product.getStockQuantity();
            if (stock != null && item.getQuantity() != null && stock < item.getQuantity()) {
                throw new BadRequestException("Insufficient stock for: " + product.getName());
            }
        }
    }

    private String normalizeIdempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.strip();
        if (!CheckoutFingerprint.IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must be 8-64 characters of letters, digits, '.', '_', ':' or '-'.");
        }
        return key;
    }

    private DeliveryDetail resolveDeliveryDetail(User user, Long deliveryDetailId) {
        if (deliveryDetailId != null) {
            // Owner-scoped: someone else's (or another tenant's) id is indistinguishable from a missing one.
            return deliveryDetailRepository.findByIdAndUser(deliveryDetailId, user)
                    .orElseThrow(() -> new ApiCodeException(HttpStatus.NOT_FOUND, "DELIVERY_DETAIL_NOT_FOUND",
                            "Delivery address not found."));
        }
        return deliveryDetailRepository.findByUserAndIsDefaultTrue(user)
                .orElseThrow(() -> new BadRequestException("Please add a delivery address to continue."));
    }

    /** A keyed retry: same key + same request returns the original answer; different request or closed order is an error. */
    private CheckoutResponse replayKeyed(Order existing, Cart cart, String couponCode, DeliveryDetail delivery,
                                         LocalDateTime openCutoff) {
        boolean open = existing.getStatus() == PaymentStatus.INITIATED && existing.getCreatedAt() != null
                && existing.getCreatedAt().isAfter(openCutoff);
        if (open) {
            boolean sameRequest = !cart.getItems().isEmpty()
                    && CheckoutFingerprint.of(cart, CheckoutFingerprint.normalizeCoupon(couponCode), delivery.getId())
                    .equals(existing.getRequestFingerprint());
            if (!sameRequest) {
                throw new ApiCodeException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different checkout request.");
            }
            Optional<CheckoutResponse> replay = replay(existing);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        throw new ApiCodeException(HttpStatus.CONFLICT, "IDEMPOTENT_ORDER_CLOSED",
                "The order created with this Idempotency-Key is no longer payable; use a new key.",
                Map.of("orderNumber", existing.getOrderNumber(), "paymentStatus", String.valueOf(existing.getStatus())));
    }

    private Optional<CheckoutResponse> replay(Order order) {
        return paymentRepository.findByOrder_OrderNumber(order.getOrderNumber())
                .filter(p -> p.getAuthorizationUrl() != null && p.getProviderPaymentReference() != null)
                .map(p -> toCheckoutResponse(order, true, "Authorization URL created",
                        new PaystackInitiateTransactionResponse.Data(p.getAuthorizationUrl(), p.getAccessCode(),
                                p.getProviderPaymentReference()), true));
    }

    private CheckoutResponse toCheckoutResponse(Order order, boolean status, String message,
                                                PaystackInitiateTransactionResponse.Data data, boolean replay) {
        return CheckoutResponse.builder()
                .status(status)
                .message(message)
                .data(data)
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .paymentStatus(order.getStatus())
                .totalAmount(order.getTotalAmount().toString())
                .expiresAt(order.getCreatedAt() == null ? null : order.getCreatedAt().plusHours(expiryHours)
                        .atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(java.time.ZoneOffset.UTC).toOffsetDateTime())
                .idempotentReplay(replay)
                .build();
    }

    private BigDecimal convertAmountToSend(BigDecimal amount) {
        return amount.multiply(new BigDecimal("100"));
    }

    private Payment createPaymentObject(Order newOrder) {
        return Payment.builder()
                .systemReference(paymentReferenceGenerator.generateReference())
                .provider(PaymentProvider.PAYSTACK)
                .order(newOrder)
                .amount(newOrder.getTotalAmount().getAmount())
                .currency(newOrder.getTotalAmount().getCurrencyCode())
                .status(PaymentStatus.PENDING)
                .build();
    }

    @Override
    public PageResponse<OrderListResponse> getMyOrders(Pageable pageable) {
        User user = authUserUtil.getCurrentUser();
        Page<Order> orders = orderRepository.findAllByUser(requireSortable(pageable), user);
        Map<Long, Payment> refs = referencesFor(orders.getContent());
        return PaginationUtil.createPageResponse(
                orders.map(o -> OrderMapper.toOrderListResponse(o, refs.get(o.getId()))));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderByOrderNumber(String orderNumber) {
        Order order = getOrder(orderNumber);
        Payment payment = referencesFor(List.of(order)).get(order.getId());
        // Account identity (name/email/id of the owner) is for an ADMIN only; customers only ever see their own orders.
        return authUserUtil.getCurrentUser().getRoles() == UserRole.ADMIN
                ? OrderMapper.toAdminOrderResponse(order, payment)
                : OrderMapper.toOrderResponse(order, payment);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderListResponse> getAllOrders(PaymentStatus paymentStatus,
                                                        OrderProcessingStatus deliveryStatus,
                                                        String from, String to, String search,
                                                        Pageable pageable) {
        Pageable page = adminPage(pageable);
        LocalDateTime fromTime = parseBound(from, "from", false);
        LocalDateTime toTime = parseBound(to, "to", true);
        if (fromTime != null && toTime != null && !fromTime.isBefore(toTime)) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "'from' must be before 'to'.");
        }
        String term = normalizeSearch(search);
        Page<Order> orders = orderRepository.findAll(adminFilter(paymentStatus, deliveryStatus, fromTime, toTime, term), page);
        Map<Long, Payment> refs = referencesFor(orders.getContent());
        return PaginationUtil.createPageResponse(
                orders.map(o -> OrderMapper.toOrderListResponse(o, refs.get(o.getId()))));
    }

    public static final int MAX_SEARCH_LENGTH = 100;
    public static final int MAX_PAGE = 1_000_000;
    public static final int MAX_PAGE_SIZE = 100;

    /** Caps page/size, applies the sort whitelist and appends the unique id as the final tie-break. */
    private Pageable adminPage(Pageable pageable) {
        requireSortable(pageable);
        if (pageable.getPageNumber() > MAX_PAGE) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "page must be <= " + MAX_PAGE + ".");
        }
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        org.springframework.data.domain.Sort sort = pageable.getSort().isSorted()
                ? pageable.getSort()
                : org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt");
        boolean hasId = sort.stream().anyMatch(o -> o.getProperty().equals("id"));
        if (!hasId) {
            org.springframework.data.domain.Sort.Direction dir = sort.iterator().next().getDirection();
            sort = sort.and(org.springframework.data.domain.Sort.by(dir, "id"));
        }
        return org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), size, sort);
    }

    /** Blank = no search; control characters or more than {@link #MAX_SEARCH_LENGTH} characters are a 400 (never truncated). */
    private static String normalizeSearch(String search) {
        if (search == null) {
            return null;
        }
        if (search.chars().anyMatch(Character::isISOControl)) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH", "q contains invalid characters.");
        }
        if (search.isBlank()) {
            return null;
        }
        String t = search.strip();
        if (t.length() > MAX_SEARCH_LENGTH) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH", "q must be at most " + MAX_SEARCH_LENGTH + " characters.");
        }
        return t;
    }

    /** LIKE wildcards in user input match literally; the pattern is lowered by the database like the column. */
    static String likePattern(String term) {
        return "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** A date (UTC day) or an ISO-8601 date-time with offset; returned as the server-local time the column stores. */
    private static LocalDateTime parseBound(String raw, String name, boolean upper) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.strip();
        try {
            java.time.Instant instant;
            if (v.length() <= 10) {
                java.time.LocalDate d = java.time.LocalDate.parse(v);
                instant = (upper ? d.plusDays(1) : d).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } else {
                instant = OffsetDateTime.parse(v).toInstant();
            }
            return instant.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        } catch (java.time.DateTimeException e) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "INVALID_DATE_FILTER",
                    "'" + name + "' must be an ISO-8601 date (2026-11-01) or date-time with offset (2026-11-01T10:00:00Z).");
        }
    }

    private static org.springframework.data.jpa.domain.Specification<Order> adminFilter(
            PaymentStatus paymentStatus, OrderProcessingStatus deliveryStatus,
            LocalDateTime from, LocalDateTime to, String term) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> all = new ArrayList<>();
            if (paymentStatus != null) {
                all.add(cb.equal(root.get("status"), paymentStatus));
            }
            if (deliveryStatus != null) {
                all.add(cb.equal(root.get("deliveryStatus"), deliveryStatus));
            }
            if (from != null) {
                all.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("createdAt"), from));
            }
            if (to != null) {
                all.add(cb.lessThan(root.<LocalDateTime>get("createdAt"), to));
            }
            if (term != null) {
                var pattern = cb.lower(cb.literal(likePattern(term)));
                var delivery = root.join("deliveryDetail", jakarta.persistence.criteria.JoinType.LEFT);
                var owner = root.join("user", jakarta.persistence.criteria.JoinType.LEFT);
                all.add(cb.or(
                        cb.like(cb.lower(root.get("orderNumber")), pattern, '\\'),
                        cb.like(cb.lower(delivery.get("recipientName")), pattern, '\\'),
                        cb.like(cb.lower(delivery.get("phoneNumber")), pattern, '\\'),
                        cb.like(cb.lower(owner.get("email")), pattern, '\\')));
            }
            return cb.and(all.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    /**
     * API-04 rework 2: one transaction per attempt, retried on a concurrency conflict with a failure webhook / expiry
     * / another cancel touching the same rows (deadlock, serialization, optimistic lock on a shared product): a
     * remaining conflict is {@code 503 TRY_AGAIN}, never a 500. The cancel is idempotent.
     */
    @Override
    public OrderResponse cancelMyOrder(String orderNumber) {
        return transientRetry.call("owner cancel", () -> new TransactionTemplate(transactionManager).execute(status -> {
            Order order = lifecycleService.cancelByOwner(orderNumber, authUserUtil.getCurrentUser());
            return OrderMapper.toOrderResponse(order, referencesFor(List.of(order)).get(order.getId()));
        }));
    }

    private Pageable requireSortable(Pageable pageable) {
        for (org.springframework.data.domain.Sort.Order o : pageable.getSort()) {
            if (!ORDER_SORT_PROPERTIES.contains(o.getProperty())) {
                throw new ApiCodeException(HttpStatus.BAD_REQUEST, "INVALID_SORT",
                        "Unsupported sort property. Allowed: " + String.join(", ", new java.util.TreeSet<>(ORDER_SORT_PROPERTIES)) + ".");
            }
        }
        return pageable;
    }

    private Map<Long, Payment> referencesFor(List<Order> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        return paymentRepository.findAllByOrderIn(orders).stream()
                .collect(Collectors.toMap(p -> p.getOrder().getId(), p -> p, (a, b) -> a));
    }

    /** Admin status change; like {@link #cancelMyOrder}, one transaction per attempt (rework 2). */
    @Override
    public OrderResponse updateOrderStatus(String orderNumber, OrderStatusUpdateRequest request, Long ifMatchVersion) {
        Long bodyVersion = request.version();
        if (bodyVersion != null && ifMatchVersion != null && !bodyVersion.equals(ifMatchVersion)) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "VERSION_MISMATCH",
                    "The version in the body and the If-Match header differ.");
        }
        Long expected = bodyVersion != null ? bodyVersion : ifMatchVersion;
        return transientRetry.call("admin order status", () -> new TransactionTemplate(transactionManager).execute(status -> {
            OrderLifecycleService.AdminUpdate result = lifecycleService.adminUpdateStatus(
                    orderNumber, request.orderStatus(), expected, authUserUtil.getCurrentUser().getEmail());
            return OrderMapper.toAdminOrderResponse(result.order(), result.payment());
        }));
    }

    @Override
    @Transactional
    public OrderResponse acknowledgeRefund(String orderNumber) {
        OrderLifecycleService.AdminUpdate result = lifecycleService.acknowledgeRefund(
                orderNumber, authUserUtil.getCurrentUser().getEmail());
        return OrderMapper.toAdminOrderResponse(result.order(), result.payment());
    }

    @Override
    public void reserveStock(String orderNumber, List<OrderItem> orderItems) {
        inventoryManagementService.reserveOrderInventory(orderNumber, orderItems);

        orderItems.forEach(item -> {
            Product product = productRepository.findById(item.getProductId()).orElse(null);
            if (product != null
                    && product.getLowStockThreshold() != null
                    && product.getStockQuantity() <= product.getLowStockThreshold()) {
                log.info("{} is low on stock, remaining items: {}", product.getName(), product.getStockQuantity());
            }
        });
    }

    @Override
    public void releaseStock(String orderNumber) {
        inventoryManagementService.releaseOrderInventory(orderNumber);
    }

    @Override
    public void finalizeReservedStock(Order order) {
        inventoryManagementService.finalizeReservedOrder(order);
    }


    // HELPER METHODS
    private Order getOrder(String orderNumber){
        User user = authUserUtil.getCurrentUser();
        String cleanOrderNumber = orderNumber.trim().toUpperCase();
        Order order;
        if(user.getRoles() == UserRole.ADMIN){
            order = orderRepository.findByOrderNumber(cleanOrderNumber)
                    .orElseThrow(() -> new ApiCodeException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", ORDER_NOT_FOUND));

        }else{
            order = orderRepository.findByOrderNumberAndUser(cleanOrderNumber,user)
                    .orElseThrow(() -> new ApiCodeException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", ORDER_NOT_FOUND));
        }
        return order;
    }

    private List<OrderItem> createOrderItems(Cart cart, Order newOrder, ShopWideDiscount activeShop, LocalDateTime pricingTime) {
    return cart.getItems().stream()
            .map(item -> OrderItemMapper.toOrderItem(item, newOrder,
                    effectivePriceService.compute(item.getProduct(), activeShop, pricingTime)))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
}

    private void validateCartItemsForEcommerce(Cart cart) {
        boolean hasUnavailableProduct = cart.getItems().stream()
                .map(CartItem::getProduct)
                .anyMatch(product -> !Boolean.TRUE.equals(product.getIsActive())
                        || !Boolean.TRUE.equals(product.getIsEnlisted()));

        if (hasUnavailableProduct) {
            throw new BadRequestException(PRODUCT_UNAVAILABLE);
        }
    }
}
