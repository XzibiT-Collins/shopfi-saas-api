package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.walk_in.request.WalkInReversalRequest;
import com.example.perfume_budget.dto.walk_in.response.WalkInOrderResponse;
import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.exception.ForbiddenException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.*;
import com.example.perfume_budget.repository.*;
import com.example.perfume_budget.service.interfaces.FrontDeskAccessService;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.WalkInReversalFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** API-RD-03 AC3 unit tests: who may reverse, key rules, state machine, side-effect order, replay without effects. */
@ExtendWith(MockitoExtension.class)
class WalkInReversalServiceUnitTest {

    @Mock private WalkInOrderRepository walkInOrderRepository;
    @Mock private WalkInOrderAuditEventRepository auditRepository;
    @Mock private WalkInCustomerRepository walkInCustomerRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProductRepository productRepository;
    @Mock private AuthUserUtil authUserUtil;
    @Mock private BookkeepingService bookkeepingService;
    @Mock private InventoryManagementService inventoryManagementService;
    @Mock private FrontDeskAccessService frontDeskAccessService;
    @InjectMocks private WalkInOrderServiceImpl service;

    private User admin;
    private WalkInOrder order;
    private static final String KEY = "reverse-key-0001";
    private static final WalkInReversalRequest REASON = new WalkInReversalRequest("Customer changed their mind");

    @BeforeEach
    void setUp() {
        admin = User.builder().id(10L).fullName("Admin").email("admin@example.com").roles(UserRole.ADMIN).build();
        order = WalkInOrder.builder().id(5L).orderNumber("WI-1").processedBy(admin)
                .subtotal(new Money(new BigDecimal("200.00"), CurrencyCode.GHS))
                .discountAmount(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                .totalTaxAmount(new Money(new BigDecimal("20.00"), CurrencyCode.GHS))
                .totalAmount(new Money(new BigDecimal("220.00"), CurrencyCode.GHS))
                .amountPaid(new Money(new BigDecimal("300.00"), CurrencyCode.GHS))
                .changeGiven(new Money(new BigDecimal("80.00"), CurrencyCode.GHS))
                .paymentMethod(WalkInPaymentMethod.CASH).status(WalkInOrderStatus.COMPLETED).receiptPrinted(false).build();
        lenient().when(authUserUtil.getCurrentUser()).thenReturn(admin);
        lenient().when(walkInOrderRepository.lockByOrderNumber("WI-1")).thenReturn(Optional.of(order));
        lenient().when(auditRepository.findByWalkInOrderIdAndIdempotencyKey(anyLong(), anyString())).thenReturn(Optional.empty());
        lenient().when(inventoryManagementService.restoreWalkInInventory("WI-1")).thenReturn(2);
    }

    @Test
    void cancelRestoresStockThenReversesTheSaleThenSetsStatusAndAudits() {
        WalkInOrderResponse r = service.cancelWalkInOrder(" wi-1 ", REASON, KEY);

        assertThat(r.status()).isEqualTo(WalkInOrderStatus.CANCELLED);
        assertThat(r.idempotentReplay()).isFalse();
        InOrder inOrder = inOrder(walkInOrderRepository, inventoryManagementService, bookkeepingService, auditRepository);
        inOrder.verify(walkInOrderRepository).lockByOrderNumber("WI-1");
        inOrder.verify(inventoryManagementService, times(1)).restoreWalkInInventory("WI-1");
        inOrder.verify(bookkeepingService, times(1)).recordWalkInReversal(order, "Admin");
        inOrder.verify(auditRepository).save(any(WalkInOrderAuditEvent.class));
        ArgumentCaptor<WalkInOrderAuditEvent> ev = ArgumentCaptor.forClass(WalkInOrderAuditEvent.class);
        verify(auditRepository).save(ev.capture());
        assertThat(ev.getValue().getEventType()).isEqualTo(WalkInAuditEventType.CANCELLED);
        assertThat(ev.getValue().getFromStatus()).isEqualTo(WalkInOrderStatus.COMPLETED);
        assertThat(ev.getValue().getToStatus()).isEqualTo(WalkInOrderStatus.CANCELLED);
        assertThat(ev.getValue().getRefundAmount()).isEqualByComparingTo("220.00");
        assertThat(ev.getValue().getActorEmail()).isEqualTo("admin@example.com");
        assertThat(ev.getValue().getIdempotencyKey()).isEqualTo(KEY);
        assertThat(ev.getValue().getRequestFingerprint()).isEqualTo(WalkInReversalFingerprint.of("CANCELLED", REASON.reason()));
    }

    @Test
    void refundTargetsRefundedAndUsesItsOwnFingerprint() {
        WalkInOrderResponse r = service.refundWalkInOrder("WI-1", REASON, KEY);
        assertThat(r.status()).isEqualTo(WalkInOrderStatus.REFUNDED);
        assertThat(WalkInReversalFingerprint.of("REFUNDED", "x")).isNotEqualTo(WalkInReversalFingerprint.of("CANCELLED", "x"));
    }

    @Test
    void onlyAnAdminMayReverseAndNothingHappensBeforeTheCheck() {
        for (UserRole role : new UserRole[]{UserRole.FRONT_DESK, UserRole.CUSTOMER}) {
            when(authUserUtil.getCurrentUser()).thenReturn(User.builder().id(11L).roles(role).build());
            lenient().when(frontDeskAccessService.hasPermission(any(), any())).thenReturn(true);
            assertThatThrownBy(() -> service.cancelWalkInOrder("WI-1", REASON, KEY)).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service.refundWalkInOrder("WI-1", REASON, KEY)).isInstanceOf(ForbiddenException.class);
        }
        verifyNoInteractions(walkInOrderRepository, inventoryManagementService, bookkeepingService, auditRepository);
        assertThat(order.getStatus()).isEqualTo(WalkInOrderStatus.COMPLETED);
    }

    @Test
    void theKeyIsMandatoryAndValidated() {
        for (String missing : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> service.cancelWalkInOrder("WI-1", REASON, missing))
                    .isInstanceOfSatisfying(ApiCodeException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
                    });
        }
        assertThatThrownBy(() -> service.refundWalkInOrder("WI-1", REASON, "short"))
                .isInstanceOfSatisfying(ApiCodeException.class, e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_INVALID"));
        verifyNoInteractions(walkInOrderRepository, inventoryManagementService, bookkeepingService, auditRepository);
    }

    @Test
    void anUnknownOrderIs404() {
        when(walkInOrderRepository.lockByOrderNumber("WI-404")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelWalkInOrder("WI-404", REASON, KEY)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(inventoryManagementService, bookkeepingService);
    }

    @Test
    void onlyACompletedOrderCanLeaveItsStatusAndTerminalStatesTouchNothing() {
        for (WalkInOrderStatus s : new WalkInOrderStatus[]{WalkInOrderStatus.CANCELLED, WalkInOrderStatus.REFUNDED}) {
            order.setStatus(s);
            assertThatThrownBy(() -> service.cancelWalkInOrder("WI-1", REASON, "other-key-0001"))
                    .isInstanceOfSatisfying(ApiCodeException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getCode()).isEqualTo("WALK_IN_ORDER_NOT_CANCELLABLE");
                        assertThat(e.body()).containsEntry("currentStatus", s.name());
                    });
            assertThatThrownBy(() -> service.refundWalkInOrder("WI-1", REASON, "other-key-0001"))
                    .isInstanceOfSatisfying(ApiCodeException.class, e -> assertThat(e.getCode()).isEqualTo("WALK_IN_ORDER_NOT_REFUNDABLE"));
            assertThat(order.getStatus()).isEqualTo(s);
        }
        verifyNoInteractions(inventoryManagementService, bookkeepingService);
        verify(auditRepository, never()).save(any());
        verify(walkInOrderRepository, never()).save(any());
    }

    @Test
    void sameKeySameRequestReplaysWithoutAnySideEffect() {
        order.setStatus(WalkInOrderStatus.CANCELLED);
        when(auditRepository.findByWalkInOrderIdAndIdempotencyKey(5L, KEY)).thenReturn(Optional.of(WalkInOrderAuditEvent.builder()
                .requestFingerprint(WalkInReversalFingerprint.of("CANCELLED", REASON.reason())).build()));

        WalkInOrderResponse r = service.cancelWalkInOrder("WI-1", new WalkInReversalRequest("  " + REASON.reason() + " "), KEY);

        assertThat(r.idempotentReplay()).isTrue();
        assertThat(r.status()).isEqualTo(WalkInOrderStatus.CANCELLED);
        verifyNoInteractions(inventoryManagementService, bookkeepingService);
        verify(auditRepository, never()).save(any());
        verify(walkInOrderRepository, never()).save(any());
    }

    @Test
    void sameKeyOtherReasonOrOtherActionIs409AndNothingHappens() {
        order.setStatus(WalkInOrderStatus.CANCELLED);
        when(auditRepository.findByWalkInOrderIdAndIdempotencyKey(5L, KEY)).thenReturn(Optional.of(WalkInOrderAuditEvent.builder()
                .requestFingerprint(WalkInReversalFingerprint.of("CANCELLED", REASON.reason())).build()));

        assertThatThrownBy(() -> service.cancelWalkInOrder("WI-1", new WalkInReversalRequest("another reason"), KEY))
                .isInstanceOfSatisfying(ApiCodeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });
        assertThatThrownBy(() -> service.refundWalkInOrder("WI-1", REASON, KEY))
                .isInstanceOfSatisfying(ApiCodeException.class, e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        verifyNoInteractions(inventoryManagementService, bookkeepingService);
    }

    @Test
    void aSaleWithNothingToPutBackIsRefusedBeforeMoneyMoves() {
        when(inventoryManagementService.restoreWalkInInventory("WI-1")).thenReturn(0);
        assertThatThrownBy(() -> service.cancelWalkInOrder("WI-1", REASON, KEY))
                .isInstanceOfSatisfying(ApiCodeException.class, e -> assertThat(e.getCode()).isEqualTo("WALK_IN_STOCK_NOT_RESTORABLE"));
        verifyNoInteractions(bookkeepingService);
        verify(auditRepository, never()).save(any());
        assertThat(order.getStatus()).isEqualTo(WalkInOrderStatus.COMPLETED);
    }

    @Test
    void aFailedLedgerPostingLeavesTheOrderCompletedAndWritesNoAudit() {
        doThrow(new com.example.perfume_budget.exception.AccountingException("boom")).when(bookkeepingService).recordWalkInReversal(any(), any());
        assertThatThrownBy(() -> service.cancelWalkInOrder("WI-1", REASON, KEY))
                .isInstanceOf(com.example.perfume_budget.exception.AccountingException.class);
        assertThat(order.getStatus()).isEqualTo(WalkInOrderStatus.COMPLETED);
        verify(auditRepository, never()).save(any());
        verify(walkInOrderRepository, never()).save(any());
    }
}
