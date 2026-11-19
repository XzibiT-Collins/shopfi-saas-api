package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.tax.OrderTaxResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.OrderTax;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** API-06b: an order_taxes row stored without tax_currency never shows "null 20.00" to a customer. */
class OrderTaxMapperTest {

    private static Money moneyWithoutCurrency(String amount) throws Exception {
        Constructor<Money> c = Money.class.getDeclaredConstructor(); // the JPA constructor: what a NULL tax_currency loads as
        c.setAccessible(true);
        Money m = c.newInstance();
        org.springframework.test.util.ReflectionTestUtils.setField(m, "amount", new BigDecimal(amount));
        return m;
    }

    private static OrderTax tax(Money amount) {
        return OrderTax.builder().id(1L).taxName("VAT").taxCode("VAT").taxRate(new BigDecimal("10.00")).taxAmount(amount).build();
    }

    @Test
    void aTaxRowWithoutACurrencyIsFormattedWithoutTheWordNull() throws Exception {
        OrderTaxResponse r = OrderTaxMapper.toOrderTaxResponse(tax(moneyWithoutCurrency("20.00")));
        assertThat(r.taxAmountFormatted()).isEqualTo("20.00").doesNotContain("null");
        assertThat(r.currency()).isNull();
        assertThat(r.taxAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void aTaxRowWithACurrencyKeepsTheCurrencyPrefix() {
        OrderTaxResponse r = OrderTaxMapper.toOrderTaxResponse(tax(new Money(new BigDecimal("20"), CurrencyCode.GHS)));
        assertThat(r.taxAmountFormatted()).isEqualTo("GHS 20.00");
        assertThat(r.currency()).isEqualTo("GHS");
    }

    @Test
    void moneyToStringOmitsAMissingCurrency() throws Exception {
        assertThat(moneyWithoutCurrency("5.50").toString()).isEqualTo("5.50");
        assertThat(new Money(new BigDecimal("5.5"), CurrencyCode.USD).toString()).isEqualTo("USD 5.50");
    }
}
