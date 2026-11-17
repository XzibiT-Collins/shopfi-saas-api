package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.AccountType;
import com.example.perfume_budget.enums.EntryType;
import com.example.perfume_budget.enums.JournalEntryType;
import com.example.perfume_budget.model.JournalEntry;
import com.example.perfume_budget.model.JournalEntryLine;
import com.example.perfume_budget.model.LedgerAccount;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.repository.JournalEntryRepository;
import com.example.perfume_budget.repository.LedgerAccountRepository;
import com.example.perfume_budget.utils.JournalEntryNumberGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** ORD-02: the SALE_REVERSAL of a paid merchant cancel and the refund settlement at acknowledgement. */
@ExtendWith(MockitoExtension.class)
class SaleReversalBookkeepingTest {

    @Mock JournalEntryRepository journal;
    @Mock LedgerAccountRepository accounts;
    @Mock JournalEntryNumberGenerator numbers;
    @Mock com.example.perfume_budget.utils.AuthUserUtil authUserUtil;
    @Mock com.example.perfume_budget.repository.ProductRepository productRepository;
    @InjectMocks BookkeepingService service;

    private final Map<AccountCategory, LedgerAccount> byCategory = new EnumMap<>(AccountCategory.class);
    private final Order order = Order.builder().orderNumber("PB-1").build();

    private LedgerAccount account(long id, AccountCategory c, AccountType t, String balance) {
        LedgerAccount a = LedgerAccount.builder().id(id).code("C" + id).name(c.name()).category(c).type(t)
                .balance(new BigDecimal(balance)).build();
        byCategory.put(c, a);
        return a;
    }

    @BeforeEach
    void setUp() {
        // balances right after the sale below was booked
        account(1, AccountCategory.CASH, AccountType.ASSET, "205.00");
        account(2, AccountCategory.SALES_REVENUE, AccountType.REVENUE, "200.00");
        account(3, AccountCategory.TAX_PAYABLE, AccountType.LIABILITY, "15.00");
        account(4, AccountCategory.DISCOUNT_EXPENSE, AccountType.EXPENSE, "10.00");
        account(5, AccountCategory.COGS, AccountType.EXPENSE, "120.00");
        account(6, AccountCategory.INVENTORY, AccountType.ASSET, "380.00");
        account(7, AccountCategory.REFUND_PAYABLE, AccountType.LIABILITY, "0.00");
        lenient().when(accounts.findByCategory(any())).thenAnswer(i -> Optional.ofNullable(byCategory.get(i.<AccountCategory>getArgument(0))));
        lenient().when(accounts.findById(any())).thenAnswer(i -> byCategory.values().stream()
                .filter(a -> a.getId().equals(i.getArgument(0))).findFirst());
        lenient().when(numbers.generate()).thenReturn("JE-1");
        // ORD-02 rework: balances move through the atomic UPDATE ... RETURNING; emulate it on the in-memory accounts
        lenient().when(accounts.applyBalanceDelta(any(), any())).thenAnswer(i -> {
            LedgerAccount a = byCategory.values().stream().filter(x -> x.getId().equals(i.getArgument(0))).findFirst().orElse(null);
            if (a == null) return null;
            a.setBalance(a.getBalance().add(i.<BigDecimal>getArgument(1)));
            return a.getBalance();
        });
    }

    private JournalEntry sale() {
        JournalEntry e = JournalEntry.builder().type(JournalEntryType.SALE).referenceType("ORDER").referenceId("PB-1").build();
        List<JournalEntryLine> lines = new ArrayList<>();
        lines.add(line(e, AccountCategory.CASH, EntryType.DEBIT, "205.00"));
        lines.add(line(e, AccountCategory.SALES_REVENUE, EntryType.CREDIT, "200.00"));
        lines.add(line(e, AccountCategory.TAX_PAYABLE, EntryType.CREDIT, "15.00"));
        lines.add(line(e, AccountCategory.DISCOUNT_EXPENSE, EntryType.DEBIT, "10.00"));
        lines.add(line(e, AccountCategory.COGS, EntryType.DEBIT, "120.00"));
        lines.add(line(e, AccountCategory.INVENTORY, EntryType.CREDIT, "120.00"));
        e.setLines(lines);
        return e;
    }

    private JournalEntryLine line(JournalEntry e, AccountCategory c, EntryType t, String amount) {
        return JournalEntryLine.builder().journalEntry(e).account(byCategory.get(c)).entryType(t)
                .amount(new BigDecimal(amount)).description(c.name()).build();
    }

    private void saleBooked() {
        when(journal.findByReferenceTypeAndReferenceIdAndTypeOrderByIdAsc("ORDER", "PB-1", JournalEntryType.SALE))
                .thenReturn(List.of(sale()));
    }

    private JournalEntry posted() {
        ArgumentCaptor<JournalEntry> c = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journal).save(c.capture());
        return c.getValue();
    }

    private BigDecimal balance(AccountCategory c) {
        return byCategory.get(c).getBalance();
    }

    private static BigDecimal sum(JournalEntry e, EntryType t) {
        return e.getLines().stream().filter(l -> l.getEntryType() == t).map(JournalEntryLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void fullReversalMovesCashIntoRefundsPayableAndNetsTheSaleToZero() {
        saleBooked();
        assertThat(service.recordSaleReversal(order, true, true, "boss@shop.test")).isTrue();
        JournalEntry e = posted();
        assertThat(e.getType()).isEqualTo(JournalEntryType.SALE_REVERSAL);
        assertThat(e.getReferenceType()).isEqualTo("ORDER");
        assertThat(e.getReferenceId()).isEqualTo("PB-1");
        assertThat(e.getRecordedBy()).isEqualTo("boss@shop.test");
        assertThat(e.getTransactionDate()).isNotNull();
        assertThat(sum(e, EntryType.DEBIT)).isEqualByComparingTo(sum(e, EntryType.CREDIT));
        assertThat(e.getLines()).noneMatch(l -> l.getAccount().getCategory() == AccountCategory.CASH);

        assertThat(balance(AccountCategory.CASH)).as("money is still in the till until refunded").isEqualByComparingTo("205.00");
        assertThat(balance(AccountCategory.REFUND_PAYABLE)).isEqualByComparingTo("205.00");
        assertThat(balance(AccountCategory.SALES_REVENUE)).isEqualByComparingTo("0");
        assertThat(balance(AccountCategory.TAX_PAYABLE)).isEqualByComparingTo("0");
        assertThat(balance(AccountCategory.DISCOUNT_EXPENSE)).isEqualByComparingTo("0");
        assertThat(balance(AccountCategory.COGS)).isEqualByComparingTo("0");
        assertThat(balance(AccountCategory.INVENTORY)).isEqualByComparingTo("500.00");
    }

    @Test
    void revenueOnlyReversalLeavesCogsAndInventoryAlone() {
        saleBooked();
        service.recordSaleReversal(order, true, false, "boss@shop.test");
        JournalEntry e = posted();
        assertThat(e.getLines()).noneMatch(l -> l.getAccount().getCategory() == AccountCategory.COGS
                || l.getAccount().getCategory() == AccountCategory.INVENTORY);
        assertThat(e.getLines()).hasSize(4);
        assertThat(balance(AccountCategory.COGS)).isEqualByComparingTo("120.00");
        assertThat(balance(AccountCategory.INVENTORY)).isEqualByComparingTo("380.00");
        assertThat(balance(AccountCategory.SALES_REVENUE)).isEqualByComparingTo("0");
    }

    @Test
    void goodsPartAfterTheRevenueWasReversedPostsOnlyCogs() {
        saleBooked();
        when(journal.countOrderLines("PB-1", JournalEntryType.SALE_REVERSAL, AccountCategory.SALES_REVENUE)).thenReturn(1L);
        assertThat(service.recordSaleReversal(order, true, true, null)).isTrue();
        JournalEntry e = posted();
        assertThat(e.getLines()).extracting(l -> l.getAccount().getCategory())
                .containsExactly(AccountCategory.COGS, AccountCategory.INVENTORY);
        assertThat(e.getLines().get(0).getEntryType()).isEqualTo(EntryType.CREDIT);
        assertThat(e.getRecordedBy()).isEqualTo("System");
        assertThat(balance(AccountCategory.REFUND_PAYABLE)).isEqualByComparingTo("0");
        assertThat(balance(AccountCategory.COGS)).isEqualByComparingTo("0");
    }

    @Test
    void nothingIsPostedTwice() {
        saleBooked();
        when(journal.countOrderLines("PB-1", JournalEntryType.SALE_REVERSAL, AccountCategory.SALES_REVENUE)).thenReturn(4L);
        when(journal.countOrderLines("PB-1", JournalEntryType.SALE_REVERSAL, AccountCategory.COGS)).thenReturn(1L);
        assertThat(service.recordSaleReversal(order, true, true, "x")).isFalse();
        verify(journal, never()).save(any());
        assertThat(balance(AccountCategory.SALES_REVENUE)).isEqualByComparingTo("200.00");
    }

    @Test
    void noSaleEntryMeansNothingToReverse() {
        assertThat(service.recordSaleReversal(order, true, true, "x")).isFalse();
        verify(journal, never()).save(any());
        verifyNoInteractions(accounts);
    }

    @Test
    void refundPaidSettlesWhatIsStillOwedOnce() {
        byCategory.get(AccountCategory.REFUND_PAYABLE).setBalance(new BigDecimal("205.00"));
        when(journal.sumOrderLines("PB-1", JournalEntryType.SALE_REVERSAL, AccountCategory.REFUND_PAYABLE, EntryType.CREDIT))
                .thenReturn(new BigDecimal("205.00"));
        when(journal.sumOrderLines("PB-1", JournalEntryType.REFUND, AccountCategory.REFUND_PAYABLE, EntryType.DEBIT))
                .thenReturn(BigDecimal.ZERO);
        assertThat(service.recordRefundPaid(order, "boss@shop.test")).isTrue();
        JournalEntry e = posted();
        assertThat(e.getType()).isEqualTo(JournalEntryType.REFUND);
        assertThat(e.getReferenceId()).isEqualTo("PB-1");
        assertThat(balance(AccountCategory.REFUND_PAYABLE)).isEqualByComparingTo("0");
        assertThat(balance(AccountCategory.CASH)).isEqualByComparingTo("0");
    }

    @Test
    void refundPaidPostsNothingWhenNothingIsOwed() {
        when(journal.sumOrderLines(eq("PB-1"), eq(JournalEntryType.SALE_REVERSAL), any(), any())).thenReturn(new BigDecimal("205.00"));
        when(journal.sumOrderLines(eq("PB-1"), eq(JournalEntryType.REFUND), any(), any())).thenReturn(new BigDecimal("205.00"));
        assertThat(service.recordRefundPaid(order, "x")).isFalse();
        verify(journal, never()).save(any());
    }

    // ------------------------------------------------------------------ ORD-02 rework F1 / F2

    @Test
    void balancesMoveByOneAtomicUpdatePerAccountInAscendingIdOrder() {
        saleBooked();
        service.recordSaleReversal(order, true, true, "x");
        org.mockito.InOrder inOrder = inOrder(accounts);
        // the reversal touches revenue(2), tax(3), discount(4), cogs(5), inventory(6), refunds payable(7): never cash(1)
        inOrder.verify(accounts).applyBalanceDelta(2L, new BigDecimal("-200.00"));
        inOrder.verify(accounts).applyBalanceDelta(3L, new BigDecimal("-15.00"));
        inOrder.verify(accounts).applyBalanceDelta(4L, new BigDecimal("-10.00"));
        inOrder.verify(accounts).applyBalanceDelta(5L, new BigDecimal("-120.00"));
        inOrder.verify(accounts).applyBalanceDelta(6L, new BigDecimal("120.00"));
        inOrder.verify(accounts).applyBalanceDelta(7L, new BigDecimal("205.00"));
        verify(accounts, never()).applyBalanceDelta(eq(1L), any());
        // no read-modify-write of the entity: nothing is re-read or saved
        verify(accounts, never()).findById(any());
        verify(accounts, never()).save(any());
    }

    @Test
    void refundWithTooLittleCashIsA409InsufficientCashWithTheBalanceAndAmount() {
        byCategory.get(AccountCategory.CASH).setBalance(new BigDecimal("100.00"));
        byCategory.get(AccountCategory.REFUND_PAYABLE).setBalance(new BigDecimal("205.00"));
        when(journal.sumOrderLines("PB-1", JournalEntryType.SALE_REVERSAL, AccountCategory.REFUND_PAYABLE, EntryType.CREDIT))
                .thenReturn(new BigDecimal("205.00"));
        when(journal.sumOrderLines("PB-1", JournalEntryType.REFUND, AccountCategory.REFUND_PAYABLE, EntryType.DEBIT))
                .thenReturn(BigDecimal.ZERO);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.recordRefundPaid(order, "x"))
                .isInstanceOfSatisfying(com.example.perfume_budget.exception.ApiCodeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("INSUFFICIENT_CASH");
                    assertThat(e.body()).containsEntry("orderNumber", "PB-1")
                            .containsEntry("currentBalance", "100.00")
                            .containsEntry("attemptedAmount", "205.00");
                });
    }

    @Test
    void aNegativeNonCriticalAccountOnlyWarns() {
        saleBooked();
        byCategory.get(AccountCategory.SALES_REVENUE).setBalance(new BigDecimal("50.00"));
        assertThat(service.recordSaleReversal(order, true, false, "x")).isTrue();
        assertThat(balance(AccountCategory.SALES_REVENUE)).isEqualByComparingTo("-150.00");
    }
}
