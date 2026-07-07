package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.tax.TaxCalculationResult;
import com.example.perfume_budget.dto.walk_in.request.WalkInCustomerRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderItemRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderRequest;
import com.example.perfume_budget.dto.walk_in.response.CustomerSearchResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInOrderResponse;
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
import com.example.perfume_budget.repository.WalkInOrderRepository;
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
    public WalkInOrderResponse placeWalkInOrder(WalkInOrderRequest request) {
        User currentUser = requireWalkInAccess(FrontDeskPermission.WALK_IN_ORDER_CREATE);
        CustomerResolution customerResolution = resolveCustomer(request);
        WalkInItemsResult itemsResult = buildOrderItems(request.items());
        List<WalkInOrderItem> orderItems = itemsResult.items();

        // No stacking: a manual discount cannot be applied when items are already on sale.
        if (itemsResult.anyOnSale() && (request.discountType() != null || request.discountValue() != null)) {
            throw new BadRequestException("Manual discount cannot be combined with active product discounts.");
        }

        BigDecimal subtotal = orderItems.stream()
                .map(WalkInOrderItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        DiscountApplication discountApplication = applyWalkInDiscount(request.discountType(), request.discountValue(), subtotal);
        BigDecimal finalPrice = discountApplication.finalPrice();

        TaxCalculationResult taxResult = taxService.calculateTaxes(finalPrice);
        BigDecimal discountAmount = discountApplication.discountAmount();
        BigDecimal totalAmount = finalPrice.add(taxResult.totalTaxAmount().getAmount()).setScale(2, RoundingMode.HALF_EVEN);
        PaymentValidation paymentValidation = validatePayment(request, totalAmount);
        BigDecimal changeGiven = calculateChange(request.paymentMethod(), paymentValidation.amountPaid(), totalAmount);

        WalkInOrder order = WalkInOrder.builder()
                .orderNumber(walkInOrderNumberGenerator.generateOrderNumber())
                .registeredUser(customerResolution.registeredUser())
                .walkInCustomer(customerResolution.walkInCustomer())
                .processedBy(currentUser)
                .subtotal(new Money(subtotal, CurrencyCode.GHS))
                .discountAmount(new Money(discountAmount, CurrencyCode.GHS))
                .automaticDiscountAmount(new Money(itemsResult.automaticDiscount(), CurrencyCode.GHS))
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
                .build();

        orderItems.forEach(item -> item.setWalkInOrder(order));
        order.setItems(orderItems);

        List<WalkInOrderTax> taxLines = buildWalkInTaxes(taxResult.orderTaxes(), order);
        order.setTaxes(taxLines);

        inventoryManagementService.consumeWalkInInventory(order.getOrderNumber(), order.getItems());
        order.getItems().forEach(item -> productRepository.incrementSoldCount(item.getProductId(), item.getQuantity()));
        WalkInOrder savedOrder = walkInOrderRepository.save(order);
        bookkeepingService.recordWalkInSale(savedOrder);

        log.info("Walk-in order {} placed by {}", savedOrder.getOrderNumber(), currentUser.getEmail());
        return WalkInOrderMapper.toResponse(savedOrder);
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

    private record WalkInItemsResult(List<WalkInOrderItem> items, boolean anyOnSale, BigDecimal automaticDiscount) {
    }
}
