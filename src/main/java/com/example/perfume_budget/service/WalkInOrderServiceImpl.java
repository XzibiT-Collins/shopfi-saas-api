package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.tax.TaxCalculationResult;
import com.example.perfume_budget.dto.walk_in.request.WalkInCustomerRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderItemRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderRequest;
import com.example.perfume_budget.dto.walk_in.response.CustomerSearchResponse;
import com.example.perfume_budget.dto.walk_in.request.WalkInQuoteRequest;
import com.example.perfume_budget.dto.walk_in.response.WalkInOrderResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInQuoteItemResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInQuoteResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInQuoteTaxResponse;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.utils.CheckoutFingerprint;
import com.example.perfume_budget.utils.WalkInFingerprint;
import org.springframework.http.HttpStatus;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.enums.FrontDeskPermission;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.enums.WalkInOrderStatus;
import com.example.perfume_budget.enums.WalkInPaymentMethod;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ForbiddenException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.mapper.WalkInOrderMapper;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.ShopWideDiscount;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.model.WalkInCustomer;
import com.example.perfume_budget.model.WalkInOrder;
import com.example.perfume_budget.model.WalkInOrderItem;
import com.example.perfume_budget.model.WalkInOrderTax;
import com.example.perfume_budget.model.OrderTax;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.repository.WalkInCustomerRepository;
import com.example.perfume_budget.repository.WalkInOrderAuditEventRepository;
import com.example.perfume_budget.repository.WalkInOrderRepository;
import com.example.perfume_budget.dto.walk_in.request.WalkInReversalRequest;
import com.example.perfume_budget.enums.WalkInAuditEventType;
import com.example.perfume_budget.model.WalkInOrderAuditEvent;
import com.example.perfume_budget.utils.WalkInReversalFingerprint;
import java.util.Map;
import com.example.perfume_budget.service.interfaces.FrontDeskAccessService;
import com.example.perfume_budget.service.interfaces.TaxService;
import com.example.perfume_budget.service.interfaces.WalkInOrderService;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.PaginationUtil;
import com.example.perfume_budget.utils.WalkInOrderNumberGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class WalkInOrderServiceImpl implements WalkInOrderService {
    private static final String WALK_IN_ORDER_NOT_FOUND = "Walk-in order not found.";

    private final WalkInOrderRepository walkInOrderRepository;
    private final WalkInOrderAuditEventRepository walkInOrderAuditEventRepository;
    private final WalkInCustomerRepository walkInCustomerRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final TaxService taxService;
    private final AuthUserUtil authUserUtil;
    private final WalkInOrderNumberGenerator walkInOrderNumberGenerator;
    private final BookkeepingService bookkeepingService;
    private final InventoryManagementService inventoryManagementService;
    private final FrontDeskAccessService frontDeskAccessService;
    private final EffectivePriceService effectivePriceService;

    @Override
    public WalkInOrderResponse placeWalkInOrder(WalkInOrderRequest request, String idempotencyKey) {
        User currentUser = requireWalkInAccess(FrontDeskPermission.WALK_IN_ORDER_CREATE);
        String key = normalizeIdempotencyKey(idempotencyKey);
        String fingerprint = null;
        if (key != null) {
            // Serialise this cashier's keyed sales in this tenant (double submit, browser re-send racing the original):
            // the second request waits here and then finds the first request's committed order instead of selling twice.
            currentUser = userRepository.lockById(currentUser.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found."));
            fingerprint = WalkInFingerprint.of(request);
            Optional<WalkInOrder> existing = walkInOrderRepository.findByProcessedByAndIdempotencyKey(currentUser, key);
            if (existing.isPresent()) {
                return replay(existing.get(), fingerprint);
            }
        }
        CustomerResolution customerResolution = resolveCustomer(request);
        Pricing pricing = price(request.items(), request.discountType(), request.discountValue());
        List<WalkInOrderItem> orderItems = pricing.items();
        BigDecimal subtotal = pricing.subtotal();
        BigDecimal discountAmount = pricing.discount().discountAmount();
        TaxCalculationResult taxResult = pricing.tax();
        BigDecimal totalAmount = pricing.totalAmount();
        DiscountApplication discountApplication = pricing.discount();
        PaymentValidation paymentValidation = validatePayment(request, totalAmount);
        BigDecimal changeGiven = calculateChange(request.paymentMethod(), paymentValidation.amountPaid(), totalAmount);

        WalkInOrder order = WalkInOrder.builder()
                .orderNumber(walkInOrderNumberGenerator.generateOrderNumber())
                .registeredUser(customerResolution.registeredUser())
                .walkInCustomer(customerResolution.walkInCustomer())
                .processedBy(currentUser)
                .subtotal(new Money(subtotal, CurrencyCode.GHS))
                .discountAmount(new Money(discountAmount, CurrencyCode.GHS))
                .automaticDiscountAmount(new Money(pricing.automaticDiscount(), CurrencyCode.GHS))
                .totalTaxAmount(new Money(taxResult.totalTaxAmount().getAmount(), CurrencyCode.GHS))
                .totalAmount(new Money(totalAmount, CurrencyCode.GHS))
                .amountPaid(new Money(paymentValidation.amountPaid(), CurrencyCode.GHS))
                .changeGiven(new Money(changeGiven, CurrencyCode.GHS))
                .discountType(discountApplication.discountType())
                .discountValue(discountApplication.discountValue())
                .paymentMethod(request.paymentMethod())
                .splitCashAmount(paymentValidation.splitCashAmount())
                .splitMobileAmount(paymentValidation.splitMobileAmount())
                .status(WalkInOrderStatus.COMPLETED)
                .receiptPrinted(false)
                .idempotencyKey(key)
                .requestFingerprint(fingerprint)
                .build();

        orderItems.forEach(item -> item.setWalkInOrder(order));
        order.setItems(orderItems);

        List<WalkInOrderTax> taxLines = buildWalkInTaxes(taxResult.orderTaxes(), order);
        order.setTaxes(taxLines);

        inventoryManagementService.consumeWalkInInventory(order.getOrderNumber(), order.getItems());
        order.getItems().forEach(item -> productRepository.incrementSoldCount(item.getProductId(), item.getQuantity()));
        WalkInOrder savedOrder = walkInOrderRepository.save(order);
        bookkeepingService.recordWalkInSale(savedOrder);

        log.info("Walk-in order {} placed by user {}", savedOrder.getOrderNumber(), currentUser.getId());
        return WalkInOrderMapper.toResponse(savedOrder).toBuilder().idempotentReplay(false).build();
    }

    /**
     * API-RD-03 AC1: prices a draft exactly like {@link #placeWalkInOrder} does (same code path) without writing
     * anything: no customer row, no stock, no sold count, no ledger entry. Read-only transaction.
     */
    @Override
    @Transactional(readOnly = true)
    public WalkInQuoteResponse quote(WalkInQuoteRequest request) {
        requireWalkInAccess(FrontDeskPermission.WALK_IN_ORDER_CREATE);
        Pricing pricing = price(request.items(), request.discountType(), request.discountValue());
        BigDecimal total = pricing.totalAmount();
        BigDecimal paid = request.amountPaid() == null ? null : scale(request.amountPaid());
        if (paid != null && paid.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Amount paid cannot be negative.");
        }
        BigDecimal change = paid != null && request.paymentMethod() == WalkInPaymentMethod.CASH
                ? calculateChange(WalkInPaymentMethod.CASH, paid, total) : null;
        BigDecimal balanceDue = paid == null ? null : total.subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN);
        DiscountApplication d = pricing.discount();
        return WalkInQuoteResponse.builder()
                .items(pricing.items().stream().map(i -> WalkInQuoteItemResponse.builder()
                        .productId(i.getProductId())
                        .productName(i.getProductName())
                        .sku(i.getSku())
                        .quantity(i.getQuantity())
                        .unitPrice(WalkInOrderMapper.formatMoney(i.getUnitPrice()))
                        .totalPrice(WalkInOrderMapper.formatMoney(i.getTotalPrice()))
                        .build()).toList())
                .originalSubtotal(WalkInOrderMapper.formatMoney(pricing.subtotal().add(pricing.automaticDiscount())))
                .automaticDiscountAmount(WalkInOrderMapper.formatMoney(pricing.automaticDiscount()))
                .subtotal(WalkInOrderMapper.formatMoney(pricing.subtotal()))
                .discountType(d.discountType())
                .discountValue(WalkInOrderMapper.formatDiscountValue(d.discountType(), d.discountValue()))
                .discountAmount(WalkInOrderMapper.formatMoney(d.discountAmount()))
                .taxes(pricing.tax().orderTaxes().stream().map(t -> WalkInQuoteTaxResponse.builder()
                        .taxName(t.getTaxName())
                        .taxCode(t.getTaxCode())
                        .taxRate(t.getTaxRate())
                        .taxableAmount(WalkInOrderMapper.formatMoney(t.getTaxableAmount().getAmount()))
                        .taxAmount(WalkInOrderMapper.formatMoney(t.getTaxAmount().getAmount()))
                        .build()).toList())
                .totalTaxAmount(WalkInOrderMapper.formatMoney(pricing.tax().totalTaxAmount().getAmount()))
                .totalAmount(WalkInOrderMapper.formatMoney(total))
                .paymentMethod(request.paymentMethod())
                .amountPaid(paid == null ? null : WalkInOrderMapper.formatMoney(paid))
                .changeGiven(change == null ? null : WalkInOrderMapper.formatMoney(change))
                .balanceDue(balanceDue == null ? null : WalkInOrderMapper.formatMoney(balanceDue))
                .build();
    }

    /** Items, discounts, tax and total of a draft: the one pricing path shared by the quote and the sale. */
    private Pricing price(List<WalkInOrderItemRequest> items, DiscountType discountType, BigDecimal discountValue) {
        WalkInItemsResult itemsResult = buildOrderItems(items);
        List<WalkInOrderItem> orderItems = itemsResult.items();

        // No stacking: a manual discount cannot be applied when items are already on sale.
        if (itemsResult.anyOnSale() && (discountType != null || discountValue != null)) {
            throw new BadRequestException("Manual discount cannot be combined with active product discounts.");
        }

        BigDecimal subtotal = orderItems.stream()
                .map(WalkInOrderItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        DiscountApplication discountApplication = applyWalkInDiscount(discountType, discountValue, subtotal);
        BigDecimal finalPrice = discountApplication.finalPrice();
        TaxCalculationResult taxResult = taxService.calculateTaxes(finalPrice);
        BigDecimal totalAmount = finalPrice.add(taxResult.totalTaxAmount().getAmount()).setScale(2, RoundingMode.HALF_EVEN);
        return new Pricing(orderItems, subtotal, itemsResult.automaticDiscount(), discountApplication, taxResult, totalAmount);
    }

    /** Same key + same body: the original sale; same key + other body: 409. Nothing is written either way. */
    private WalkInOrderResponse replay(WalkInOrder existing, String fingerprint) {
        if (!fingerprint.equals(existing.getRequestFingerprint())) {
            throw new ApiCodeException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different walk-in sale.");
        }
        log.info("Walk-in order {} replayed for user {}", existing.getOrderNumber(), existing.getProcessedBy().getId());
        return WalkInOrderMapper.toResponse(existing).toBuilder().idempotentReplay(true).build();
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

    @Override
    @Transactional(readOnly = true)
    public List<CustomerSearchResponse> searchCustomers(String query) {
        requireWalkInAccess(FrontDeskPermission.CUSTOMER_SEARCH);
        String cleanedQuery = query == null ? "" : query.strip();
        if (cleanedQuery.isBlank()) {
            return List.of();
        }

        return userRepository.searchUsersByNameOrEmailAndRole(cleanedQuery, UserRole.CUSTOMER)
                .stream()
                .map(WalkInOrderMapper::toCustomerSearchResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<WalkInOrderResponse> getWalkInOrders(LocalDate date, Pageable pageable) {
        requireWalkInAccess(FrontDeskPermission.WALK_IN_ORDER_VIEW);
        Page<WalkInOrderResponse> page;
        if (date == null) {
            page = walkInOrderRepository.findAllByOrderByCreatedAtDesc(pageable)
                    .map(WalkInOrderMapper::toResponse);
        } else {
            LocalDateTime start = date.atStartOfDay();
            page = walkInOrderRepository.findAllByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(start, pageable)
                    .map(WalkInOrderMapper::toResponse);
        }
        return PaginationUtil.createPageResponse(page);
    }

    @Override
    @Transactional(readOnly = true)
    public WalkInOrderResponse getWalkInOrder(String orderNumber) {
        requireWalkInAccess(FrontDeskPermission.WALK_IN_ORDER_VIEW);
        return WalkInOrderMapper.toResponse(getWalkInOrderEntity(orderNumber));
    }

    @Override
    public void markReceiptPrinted(String orderNumber) {
        requireWalkInAccess(FrontDeskPermission.WALK_IN_ORDER_MARK_RECEIPT_PRINTED);
        WalkInOrder order = getWalkInOrderEntity(orderNumber);
        if (!Boolean.TRUE.equals(order.getReceiptPrinted())) {
            order.setReceiptPrinted(true);
            walkInOrderRepository.save(order);
        }
    }

    @Override
    public WalkInOrderResponse cancelWalkInOrder(String orderNumber, WalkInReversalRequest request, String idempotencyKey) {
        return reverse(orderNumber, request, idempotencyKey, WalkInAuditEventType.CANCELLED);
    }

    @Override
    public WalkInOrderResponse refundWalkInOrder(String orderNumber, WalkInReversalRequest request, String idempotencyKey) {
        return reverse(orderNumber, request, idempotencyKey, WalkInAuditEventType.REFUNDED);
    }

    /**
     * API-RD-03 AC3: the one path of cancel and refund (they differ only in the target status and the error code).
     * Order of events, all in this transaction (any failure rolls ALL of it back, so nothing is half done):
     * ADMIN check, key check, order row lock, replay / state guard under the lock, stock back, sale reversed, status,
     * audit row. Lock order: order row -> products (id order) -> location rows -> ledger accounts (id order), the
     * same direction as the sale, so a sale and a reversal of the same product cannot deadlock.
     */
    private WalkInOrderResponse reverse(String orderNumber, WalkInReversalRequest request, String rawKey,
                                        WalkInAuditEventType type) {
        User actor = requireAdmin();
        String key = requireIdempotencyKey(rawKey);
        String reason = request.reason().strip();
        String fingerprint = WalkInReversalFingerprint.of(type.name(), reason);

        WalkInOrder order = walkInOrderRepository.lockByOrderNumber(orderNumber.trim().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException(WALK_IN_ORDER_NOT_FOUND));

        Optional<WalkInOrderAuditEvent> prior = walkInOrderAuditEventRepository
                .findByWalkInOrderIdAndIdempotencyKey(order.getId(), key);
        if (prior.isPresent()) {
            if (!fingerprint.equals(prior.get().getRequestFingerprint())) {
                throw new ApiCodeException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different request on this walk-in order.");
            }
            log.info("Walk-in order {} {} replayed", order.getOrderNumber(), type);
            return WalkInOrderMapper.toResponse(order).toBuilder().idempotentReplay(true).build();
        }

        WalkInOrderStatus target = type == WalkInAuditEventType.CANCELLED ? WalkInOrderStatus.CANCELLED : WalkInOrderStatus.REFUNDED;
        if (order.getStatus() != WalkInOrderStatus.COMPLETED) {
            String code = type == WalkInAuditEventType.CANCELLED ? "WALK_IN_ORDER_NOT_CANCELLABLE" : "WALK_IN_ORDER_NOT_REFUNDABLE";
            throw new ApiCodeException(HttpStatus.CONFLICT, code,
                    "Only a COMPLETED walk-in order can be " + (type == WalkInAuditEventType.CANCELLED ? "cancelled" : "refunded")
                            + ". This order is " + order.getStatus() + ".",
                    Map.of("currentStatus", order.getStatus().name()));
        }

        int unitsRestored = inventoryManagementService.restoreWalkInInventory(order.getOrderNumber());
        if (unitsRestored == 0) {
            throw new ApiCodeException(HttpStatus.CONFLICT, "WALK_IN_STOCK_NOT_RESTORABLE",
                    "This sale has no recorded stock movements to put back, so it cannot be reversed automatically.",
                    Map.of("currentStatus", order.getStatus().name()));
        }
        bookkeepingService.recordWalkInReversal(order, actor.getFullName());

        WalkInOrderStatus from = order.getStatus();
        order.setStatus(target);
        walkInOrderRepository.save(order);
        walkInOrderAuditEventRepository.save(WalkInOrderAuditEvent.builder()
                .walkInOrderId(order.getId())
                .eventType(type)
                .fromStatus(from)
                .toStatus(target)
                .actorId(actor.getId())
                .actorEmail(actor.getEmail())
                .reason(reason)
                .refundAmount(order.getTotalAmount().getAmount())
                .idempotencyKey(key)
                .requestFingerprint(fingerprint)
                .createdAt(LocalDateTime.now())
                .build());
        // no reason text and no customer data in the log
        log.info("Walk-in order {} {} by user {}", order.getOrderNumber(), target, actor.getId());
        return WalkInOrderMapper.toResponse(order).toBuilder().idempotentReplay(false).build();
    }

    /** Cancel / refund are ADMIN-only: no FrontDeskPermission exists for them and none can be granted through the API. */
    private User requireAdmin() {
        User currentUser = authUserUtil.getCurrentUser();
        if (currentUser == null) {
            throw new BadRequestException("Authenticated user is required.");
        }
        if (currentUser.getRoles() != UserRole.ADMIN) {
            throw new ForbiddenException("Only an administrator can cancel or refund a walk-in order.");
        }
        return currentUser;
    }

    /** Unlike the sale, cancel and refund move money and stock, so the key is mandatory. */
    private String requireIdempotencyKey(String raw) {
        String key = normalizeIdempotencyKey(raw);
        if (key == null) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "The Idempotency-Key header is required to cancel or refund a walk-in order.");
        }
        return key;
    }

    private User requireWalkInAccess(FrontDeskPermission permission) {
        User currentUser = authUserUtil.getCurrentUser();
        if (currentUser == null) {
            throw new BadRequestException("Authenticated user is required.");
        }
        if (currentUser.getRoles() == UserRole.ADMIN) {
            return currentUser;
        }
        if (currentUser.getRoles() == UserRole.FRONT_DESK
                && frontDeskAccessService.hasPermission(currentUser, permission)) {
            return currentUser;
        }
        throw new ForbiddenException("You do not have permission to perform this walk-in action.");
    }

    private CustomerResolution resolveCustomer(WalkInOrderRequest request) {
        if (request.registeredUserId() != null) {
            User registeredUser = userRepository.findById(request.registeredUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("Registered customer not found."));
            if (registeredUser.getRoles() != UserRole.CUSTOMER) {
                throw new BadRequestException("Selected registered user is not a customer.");
            }
            return new CustomerResolution(registeredUser, null);
        }

        if (request.walkInCustomer() != null) {
            WalkInCustomer walkInCustomer = walkInCustomerRepository.save(buildWalkInCustomer(request.walkInCustomer(), false));
            return new CustomerResolution(null, walkInCustomer);
        }

        WalkInCustomer anonymousCustomer = walkInCustomerRepository.save(
                WalkInCustomer.builder()
                        .isAnonymous(true)
                        .build()
        );
        return new CustomerResolution(null, anonymousCustomer);
    }

    private WalkInCustomer buildWalkInCustomer(WalkInCustomerRequest request, boolean anonymous) {
        return WalkInCustomer.builder()
                .name(normalizeOptional(request.name()))
                .phone(normalizeOptional(request.phone()))
                .email(normalizeOptional(request.email()))
                .isAnonymous(anonymous)
                .build();
    }

    private WalkInItemsResult buildOrderItems(List<WalkInOrderItemRequest> items) {
        // Resolve effective (discounted) prices once for the whole order.
        ShopWideDiscount activeShop = effectivePriceService.activeShopDiscount().orElse(null);
        LocalDateTime pricingTime = effectivePriceService.now();

        List<WalkInOrderItem> orderItems = new ArrayList<>();
        boolean anyOnSale = false;
        BigDecimal automaticDiscount = BigDecimal.ZERO;
        for (WalkInOrderItemRequest itemRequest : items) {
            Product product = productRepository.findById(itemRequest.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + itemRequest.productId()));

            if (!Boolean.TRUE.equals(product.getIsActive())) {
                throw new BadRequestException("Product is inactive: " + product.getName());
            }
            if (product.getStockQuantity() < itemRequest.quantity()) {
                throw new BadRequestException("Insufficient stock for: " + product.getName());
            }

            EffectivePrice effectivePrice = effectivePriceService.compute(product, activeShop, pricingTime);
            if (effectivePrice.onSale()) {
                anyOnSale = true;
            }
            BigDecimal quantity = BigDecimal.valueOf(itemRequest.quantity());
            BigDecimal unitPrice = scale(effectivePrice.effectiveAmount());
            BigDecimal totalPrice = scale(unitPrice.multiply(quantity));
            automaticDiscount = automaticDiscount.add(
                    effectivePrice.originalAmount().subtract(effectivePrice.effectiveAmount()).multiply(quantity));

            orderItems.add(WalkInOrderItem.builder()
                    .productId(product.getId())
                    .productName(product.getName())
                    .sku(product.getSku())
                    .quantity(itemRequest.quantity())
                    .unitPrice(unitPrice)
                    .costPrice(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN))
                    .totalPrice(totalPrice)
                    .build());
        }
        return new WalkInItemsResult(orderItems, anyOnSale, scale(automaticDiscount));
    }

    private DiscountApplication applyWalkInDiscount(DiscountType discountType,
                                                    BigDecimal discountValue,
                                                    BigDecimal orderSubtotal) {
        if (discountType == null && discountValue == null) {
            return new DiscountApplication(null, null,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN),
                    orderSubtotal.setScale(2, RoundingMode.HALF_EVEN));
        }
        if (discountType == null || discountValue == null) {
            throw new BadRequestException("Discount type and discount value must be provided together.");
        }

        BigDecimal normalizedDiscountValue = scale(discountValue);
        if (normalizedDiscountValue.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Discount value cannot be negative.");
        }
        if (discountType == DiscountType.PERCENTAGE && normalizedDiscountValue.compareTo(new BigDecimal("100.00")) > 0) {
            throw new BadRequestException("Percentage discount cannot exceed 100.");
        }

        BigDecimal discountAmount = switch (discountType) {
            case PERCENTAGE -> {
                BigDecimal calculated = orderSubtotal
                        .multiply(normalizedDiscountValue)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                yield calculated.min(orderSubtotal);
            }
            case FLAT -> normalizedDiscountValue.min(orderSubtotal);
        };
        BigDecimal finalPrice = orderSubtotal.subtract(discountAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN);
        return new DiscountApplication(discountType, normalizedDiscountValue, discountAmount.setScale(2, RoundingMode.HALF_EVEN), finalPrice);
    }

    private PaymentValidation validatePayment(WalkInOrderRequest request, BigDecimal totalAmount) {
        BigDecimal amountPaid = scale(request.amountPaid());
        BigDecimal splitCashAmount = request.splitCashAmount() != null ? scale(request.splitCashAmount()) : null;
        BigDecimal splitMobileAmount = request.splitMobileAmount() != null ? scale(request.splitMobileAmount()) : null;
        WalkInPaymentMethod paymentMethod = request.paymentMethod();

        switch (paymentMethod) {
            case CASH -> {
                if (amountPaid.compareTo(totalAmount) < 0) {
                    throw new BadRequestException("Amount paid must be at least the order total for cash payments.");
                }
            }
            case MOBILE_MONEY, CARD -> {
                if (amountPaid.compareTo(totalAmount) != 0) {
                    throw new BadRequestException("Amount paid must equal the order total for " + paymentMethod + " payments.");
                }
            }
            case SPLIT -> {
                BigDecimal cashPortion = Optional.ofNullable(splitCashAmount).orElse(BigDecimal.ZERO);
                BigDecimal mobilePortion = Optional.ofNullable(splitMobileAmount).orElse(BigDecimal.ZERO);
                BigDecimal splitTotal = cashPortion.add(mobilePortion).setScale(2, RoundingMode.HALF_EVEN);

                if (splitTotal.compareTo(totalAmount) != 0) {
                    throw new BadRequestException("Split payment total must equal the order total.");
                }
                if (amountPaid.compareTo(splitTotal) != 0) {
                    throw new BadRequestException("Amount paid must equal the combined split amounts.");
                }
            }
        }

        return new PaymentValidation(amountPaid, splitCashAmount, splitMobileAmount);
    }

    private BigDecimal calculateChange(WalkInPaymentMethod paymentMethod, BigDecimal amountPaid, BigDecimal totalAmount) {
        if (paymentMethod == WalkInPaymentMethod.MOBILE_MONEY || paymentMethod == WalkInPaymentMethod.CARD) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        }
        return amountPaid.subtract(totalAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN);
    }

    private List<WalkInOrderTax> buildWalkInTaxes(List<OrderTax> orderTaxes, WalkInOrder order) {
        return orderTaxes.stream()
                .map(tax -> WalkInOrderTax.builder()
                        .walkInOrder(order)
                        .taxName(tax.getTaxName())
                        .taxCode(tax.getTaxCode())
                        .taxRate(tax.getTaxRate())
                        .taxableAmount(scale(tax.getTaxableAmount().getAmount()))
                        .taxAmount(scale(tax.getTaxAmount().getAmount()))
                        .build())
                .toList();
    }

    private WalkInOrder getWalkInOrderEntity(String orderNumber) {
        return walkInOrderRepository.findByOrderNumber(orderNumber.trim().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException(WALK_IN_ORDER_NOT_FOUND));
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isBlank() ? null : normalized;
    }

    private BigDecimal scale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_EVEN);
    }

    private record CustomerResolution(User registeredUser, WalkInCustomer walkInCustomer) {
    }

    private record PaymentValidation(BigDecimal amountPaid, BigDecimal splitCashAmount, BigDecimal splitMobileAmount) {
    }

    private record DiscountApplication(DiscountType discountType,
                                       BigDecimal discountValue,
                                       BigDecimal discountAmount,
                                       BigDecimal finalPrice) {
    }

    private record Pricing(List<WalkInOrderItem> items, BigDecimal subtotal, BigDecimal automaticDiscount,
                           DiscountApplication discount, TaxCalculationResult tax, BigDecimal totalAmount) {
    }

    private record WalkInItemsResult(List<WalkInOrderItem> items, boolean anyOnSale, BigDecimal automaticDiscount) {
    }
}
