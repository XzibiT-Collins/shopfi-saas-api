package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderMapperAdminTest {

    private Order order() {
        User u = User.builder().id(7L).fullName("Ama Mensah").email("ama@example.com").build();
        OrderTax tax = OrderTax.builder().id(1L).taxName("VAT").taxCode("VAT").taxRate(new BigDecimal("15.00"))
                .taxAmount(new Money(new BigDecimal("3.00"), CurrencyCode.GHS)).build();
        return Order.builder().id(1L).orderNumber("PB-1").user(u).version(4L)
                .subtotal(new Money(new BigDecimal("20"), CurrencyCode.GHS))
                .totalAmount(new Money(new BigDecimal("23"), CurrencyCode.GHS))
                .totalTaxAmount(new Money(new BigDecimal("3"), CurrencyCode.GHS))
                .status(PaymentStatus.COMPLETED).deliveryStatus(OrderProcessingStatus.PENDING)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).items(new ArrayList<>()).taxes(new ArrayList<>(List.of(tax)))
                .deliveryDetail(DeliveryDetail.builder().recipientName("R").phoneNumber("1").addressLine1("a").city("c").region("r")
                        .label(DeliveryLabel.HOME).isDefault(true).build()).build();
    }

    @Test
    void adminViewCarriesAccountAndCustomerViewDoesNot() {
        Payment p = Payment.builder().providerPaymentReference("T1").refundRequired(true).build();
        OrderResponse admin = OrderMapper.toAdminOrderResponse(order(), p);
        assertThat(admin.account().email()).isEqualTo("ama@example.com");
        assertThat(admin.account().id()).isEqualTo(7L);
        assertThat(admin.account().fullName()).isEqualTo("Ama Mensah");
        assertThat(admin.reference()).isEqualTo("T1");
        assertThat(admin.refundRequired()).isTrue();
        assertThat(admin.version()).isEqualTo(4L);
        assertThat(OrderMapper.toOrderResponse(order(), p).account()).isNull();
        assertThat(OrderMapper.toOrderResponse(order(), "T1").account()).isNull();
    }

    @Test
    void orderedAtIsTheSameInstantAsOrderDateInUtc() {
        OrderResponse r = OrderMapper.toOrderResponse(order(), (String) null);
        assertThat(r.orderedAt().getOffset()).isEqualTo(java.time.ZoneOffset.UTC);
        assertThat(r.orderedAt().toInstant()).isEqualTo(r.orderDate().atZone(ZoneId.systemDefault()).toInstant());
        assertThat(OrderMapper.toUtc(null)).isNull();
    }

    @Test
    void taxFieldsAreUnambiguousAndLegacyOnesUnchanged() {
        var t = OrderMapper.toOrderResponse(order(), (String) null).taxes().orderTaxes().get(0);
        assertThat(t.taxAmount()).isEqualByComparingTo("3.00");
        assertThat(t.taxRate()).isEqualByComparingTo("15.00");
        assertThat(t.taxRatePercent()).isEqualByComparingTo("15.00");
        assertThat(t.currency()).isEqualTo("GHS");
        assertThat(t.taxAmountFormatted()).isEqualTo("GHS 3.00");
        assertThat(t.taxCode()).isEqualTo("VAT");
    }

    @Test
    void cancelInfoIsExposedAsUtcAndSource() {
        Order o = order();
        o.setCancelledAt(LocalDateTime.of(2026, 1, 2, 9, 0));
        o.setCancelSource(CancelSource.ADMIN);
        OrderResponse r = OrderMapper.toOrderResponse(o, (String) null);
        assertThat(r.cancelSource()).isEqualTo("ADMIN");
        assertThat(r.cancelledAt()).isNotNull();
        assertThat(OrderMapper.toOrderListResponse(o, (String) null).version()).isEqualTo(4L);
    }
}
