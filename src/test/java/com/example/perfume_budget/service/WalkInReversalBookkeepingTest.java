package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.exception.AccountingException;
import com.example.perfume_budget.model.*;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** API-RD-03 AC3: the WALK_IN_REVERSAL entry mirrors the WALK_IN_SALE entry line by line, once. */
@ExtendWith(MockitoExtension.class)
class WalkInReversalBookkeepingTest {

    @Mock JournalEntryRepository journal;
    @Mock LedgerAccountRepository accounts;
    @Mock JournalEntryNumberGenerator numbers;
    @Mock com.example.perfume_budget.utils.AuthUserUtil authUserUtil;
    @Mock com.example.perfume_budget.repository.ProductRepository productRepository;
    @InjectMocks BookkeepingService service;

    private final Map<AccountCategory, LedgerAccount> byCategory = new EnumMap<>(AccountCategory.class);
    private final WalkInOrder order = WalkInOrder.builder().orderNumber("WI-9").build();

    private LedgerAccount account(long id, AccountCategory c, AccountType t, String balance) {
        LedgerAccount a = LedgerAccount.builder().id(id).code("C" + id).name(c.name()).category(c).type(t)
                .balance(new BigDecimal(balance)).build();
        byCategory.put(c, a);
        return a;
    }

    private JournalEntryLine line(JournalEntry e, AccountCategory c, EntryType t, String amount) {
        return JournalEntryLine.builder().journalEntry(e).account(byCategory.get(c)).entryType(t)
                .amount(new BigDecimal(amount)).description("sale " + c).build();
    }

    private JournalEntry sale() {
        JournalEntry e = JournalEntry.builder().type(JournalEntryType.WALK_IN_SALE).referenceType("WALK_IN_ORDER").referenceId("WI-9").build();
        List<JournalEntryLine> ls = new ArrayList<>();
        ls.add(line(e, AccountCategory.CASH, EntryType.DEBIT, "110.00"));
        ls.add(line(e, AccountCategory.SALES_REVENUE, EntryType.CREDIT, "100.00"));
        ls.add(line(e, AccountCategory.TAX_PAYABLE, EntryType.CREDIT, "15.00"));
        ls.add(line(e, AccountCategory.DISCOUNT_EXPENSE, EntryType.DEBIT, "5.00"));
        ls.add(line(e, AccountCategory.COGS, EntryType.DEBIT, "40.00"));
        ls.add(line(e, AccountCategory.INVENTORY, EntryType.CREDIT, "40.00"));
        e.setLines(ls);
        return e;
    }

    @BeforeEach
    void setUp() {
        account(1, AccountCategory.CASH, AccountType.ASSET, "110.00");
        account(2, AccountCategory.SALES_REVENUE, AccountType.REVENUE, "100.00");
        account(3, AccountCategory.TAX_PAYABLE, AccountType.LIABILITY, "15.00");
        account(4, AccountCategory.DISCOUNT_EXPENSE, AccountType.EXPENSE, "5.00");
        account(5, AccountCategory.COGS, AccountType.EXPENSE, "40.00");
        account(6, AccountCategory.INVENTORY, AccountType.ASSET, "60.00");
        lenient().when(numbers.generate()).thenReturn("JE-1");
        lenient().when(journal.findByReferenceTypeAndReferenceIdAndTypeOrderByIdAsc("WALK_IN_ORDER", "WI-9", JournalEntryType.WALK_IN_SALE))
                .thenReturn(List.of(sale()));
        lenient().when(journal.findByReferenceTypeAndReferenceIdAndTypeOrderByIdAsc("WALK_IN_ORDER", "WI-9", JournalEntryType.WALK_IN_REVERSAL))
                .thenReturn(List.of());
        lenient().when(accounts.applyBalanceDelta(anyLong(), any())).thenReturn(BigDecimal.TEN);
    }

    @Test
    void everySaleLineIsMirroredOnTheOppositeSideWithTheSameAmount() {
        service.recordWalkInReversal(order, "Admin");

        ArgumentCaptor<JournalEntry> cap = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journal).save(cap.capture());
        JournalEntry e = cap.getValue();
        assertThat(e.getType()).isEqualTo(JournalEntryType.WALK_IN_REVERSAL);
        assertThat(e.getReferenceType()).isEqualTo("WALK_IN_ORDER");
        assertThat(e.getReferenceId()).isEqualTo("WI-9");
        assertThat(e.getRecordedBy()).isEqualTo("Admin");
        assertThat(e.getLines()).hasSize(6);
        Map<AccountCategory, String> got = new EnumMap<>(AccountCategory.class);
        e.getLines().forEach(l -> got.put(l.getAccount().getCategory(), l.getEntryType() + " " + l.getAmount()));
        assertThat(got).containsEntry(AccountCategory.CASH, "CREDIT 110.00")           // paid back at the counter
                .containsEntry(AccountCategory.SALES_REVENUE, "DEBIT 100.00")
                .containsEntry(AccountCategory.TAX_PAYABLE, "DEBIT 15.00")
                .containsEntry(AccountCategory.DISCOUNT_EXPENSE, "CREDIT 5.00")
                .containsEntry(AccountCategory.COGS, "CREDIT 40.00")
                .containsEntry(AccountCategory.INVENTORY, "DEBIT 40.00");
        // balances: cash -110, revenue -100, tax -15, discount -5, cogs -40, inventory +40
        verify(accounts).applyBalanceDelta(1L, new BigDecimal("-110.00"));
        verify(accounts).applyBalanceDelta(6L, new BigDecimal("40.00"));
    }

    @Test
    void aSecondReversalOfTheSameOrderFailsLoudlyAndPostsNothing() {
        when(journal.findByReferenceTypeAndReferenceIdAndTypeOrderByIdAsc("WALK_IN_ORDER", "WI-9", JournalEntryType.WALK_IN_REVERSAL))
                .thenReturn(List.of(JournalEntry.builder().build()));
        assertThatThrownBy(() -> service.recordWalkInReversal(order, "Admin")).isInstanceOf(AccountingException.class);
        verify(journal, never()).save(any());
        verify(accounts, never()).applyBalanceDelta(anyLong(), any());
    }

    @Test
    void noSaleEntryMeansNothingToReverseAndTheCancelFails() {
        when(journal.findByReferenceTypeAndReferenceIdAndTypeOrderByIdAsc("WALK_IN_ORDER", "WI-9", JournalEntryType.WALK_IN_SALE))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.recordWalkInReversal(order, "Admin")).isInstanceOf(AccountingException.class);
        verify(journal, never()).save(any());
    }

    @Test
    void notEnoughCashToPayBackIs409InsufficientCashAndTheEntryRollsBack() {
        when(accounts.applyBalanceDelta(1L, new BigDecimal("-110.00"))).thenReturn(new BigDecimal("-10.00"));
        assertThatThrownBy(() -> service.recordWalkInReversal(order, "Admin"))
                .isInstanceOfSatisfying(com.example.perfume_budget.exception.ApiCodeException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INSUFFICIENT_CASH");
                    assertThat(e.getStatus().value()).isEqualTo(409);
                });
    }
}
