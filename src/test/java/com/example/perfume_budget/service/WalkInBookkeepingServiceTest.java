package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.model.*;
import com.example.perfume_budget.repository.JournalEntryRepository;
import com.example.perfume_budget.repository.LedgerAccountRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.JournalEntryNumberGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalkInBookkeepingServiceTest {

    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private LedgerAccountRepository ledgerAccountRepository;
    @Mock
    private JournalEntryNumberGenerator entryNumberGenerator;
    @Mock
    private AuthUserUtil authUserUtil;
    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private BookkeepingService bookkeepingService;

    private LedgerAccount cashAccount;
    private LedgerAccount mobileMoneyAccount;
    private LedgerAccount revenueAccount;
    private LedgerAccount taxPayableAccount;
    private LedgerAccount discountExpenseAccount;
    private LedgerAccount cogsAccount;
    private LedgerAccount inventoryAccount;

    @BeforeEach
    void setUp() {
        cashAccount = createAccount(1L, AccountCategory.CASH, AccountType.ASSET, BigDecimal.ZERO);
        mobileMoneyAccount = createAccount(2L, AccountCategory.MOBILE_MONEY, AccountType.ASSET, BigDecimal.ZERO);
        revenueAccount = createAccount(3L, AccountCategory.SALES_REVENUE, AccountType.REVENUE, BigDecimal.ZERO);
        taxPayableAccount = createAccount(4L, AccountCategory.TAX_PAYABLE, AccountType.LIABILITY, BigDecimal.ZERO);
        discountExpenseAccount = createAccount(5L, AccountCategory.DISCOUNT_EXPENSE, AccountType.EXPENSE, BigDecimal.ZERO);
        cogsAccount = createAccount(6L, AccountCategory.COGS, AccountType.EXPENSE, BigDecimal.ZERO);
        inventoryAccount = createAccount(7L, AccountCategory.INVENTORY, AccountType.ASSET, new BigDecimal("1000.00"));

        lenient().when(entryNumberGenerator.generate()).thenReturn("JE-WLK-001");
        when(ledgerAccountRepository.findByCategory(AccountCategory.CASH)).thenReturn(Optional.of(cashAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.MOBILE_MONEY)).thenReturn(Optional.of(mobileMoneyAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.SALES_REVENUE)).thenReturn(Optional.of(revenueAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.TAX_PAYABLE)).thenReturn(Optional.of(taxPayableAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.DISCOUNT_EXPENSE)).thenReturn(Optional.of(discountExpenseAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.COGS)).thenReturn(Optional.of(cogsAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.INVENTORY)).thenReturn(Optional.of(inventoryAccount));

        lenient().when(ledgerAccountRepository.findById(cashAccount.getId())).thenReturn(Optional.of(cashAccount));
        lenient().when(ledgerAccountRepository.findById(mobileMoneyAccount.getId())).thenReturn(Optional.of(mobileMoneyAccount));
        lenient().when(ledgerAccountRepository.findById(revenueAccount.getId())).thenReturn(Optional.of(revenueAccount));
        lenient().when(ledgerAccountRepository.findById(taxPayableAccount.getId())).thenReturn(Optional.of(taxPayableAccount));
        lenient().when(ledgerAccountRepository.findById(discountExpenseAccount.getId())).thenReturn(Optional.of(discountExpenseAccount));
        lenient().when(ledgerAccountRepository.findById(cogsAccount.getId())).thenReturn(Optional.of(cogsAccount));
        lenient().when(ledgerAccountRepository.findById(inventoryAccount.getId())).thenReturn(Optional.of(inventoryAccount));
    }

    @Test
    @DisplayName("Record Walk-In Sale - Mobile money journals against mobile money asset")
    void recordWalkInSale_MobileMoney_Success() {
        WalkInOrder order = createWalkInOrder(WalkInPaymentMethod.MOBILE_MONEY, null, null);

        bookkeepingService.recordWalkInSale(order);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        JournalEntry entry = captor.getValue();

        assertEquals(JournalEntryType.WALK_IN_SALE, entry.getType());
        assertEquals("WALK_IN_ORDER", entry.getReferenceType());
        assertTrue(entry.getDescription().contains("MOBILE_MONEY"));
        assertLine(entry.getLines(), AccountCategory.MOBILE_MONEY, EntryType.DEBIT, new BigDecimal("110.00"));
    }

    @Test
    @DisplayName("Record Walk-In Sale - Split journals cash and mobile money separately")
    void recordWalkInSale_Split_Success() {
        WalkInOrder order = createWalkInOrder(WalkInPaymentMethod.SPLIT, new BigDecimal("40.00"), new BigDecimal("70.00"));

        bookkeepingService.recordWalkInSale(order);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        List<JournalEntryLine> lines = captor.getValue().getLines();

        assertLine(lines, AccountCategory.CASH, EntryType.DEBIT, new BigDecimal("40.00"));
        assertLine(lines, AccountCategory.MOBILE_MONEY, EntryType.DEBIT, new BigDecimal("70.00"));
    }

    @Test
    @DisplayName("Record Walk-In Sale - Discount remains discount expense")
    void recordWalkInSale_WithDiscount_Success() {
        WalkInOrder order = createWalkInOrder(WalkInPaymentMethod.CASH, null, null);
        order.setDiscountAmount(new Money(new BigDecimal("10.00"), CurrencyCode.GHS));
        order.setTotalAmount(new Money(new BigDecimal("100.00"), CurrencyCode.GHS));
        order.setAmountPaid(new Money(new BigDecimal("100.00"), CurrencyCode.GHS));

        bookkeepingService.recordWalkInSale(order);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        List<JournalEntryLine> lines = captor.getValue().getLines();

        assertLine(lines, AccountCategory.DISCOUNT_EXPENSE, EntryType.DEBIT, new BigDecimal("10.00"));
        assertLine(lines, AccountCategory.SALES_REVENUE, EntryType.CREDIT, new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("Record Walk-In Sale - Automatic (product/shop) discount books gross revenue + discount line")
    void recordWalkInSale_WithAutomaticDiscount_Success() {
        WalkInOrder order = createWalkInOrder(WalkInPaymentMethod.CASH, null, null);
        // net subtotal 80, automatic discount 20 -> gross 100; tax 10; total 90
        order.setSubtotal(new Money(new BigDecimal("80.00"), CurrencyCode.GHS));
        order.setAutomaticDiscountAmount(new Money(new BigDecimal("20.00"), CurrencyCode.GHS));
        order.setTotalTaxAmount(new Money(new BigDecimal("10.00"), CurrencyCode.GHS));
        order.setTotalAmount(new Money(new BigDecimal("90.00"), CurrencyCode.GHS));
        order.setAmountPaid(new Money(new BigDecimal("90.00"), CurrencyCode.GHS));

        bookkeepingService.recordWalkInSale(order);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        List<JournalEntryLine> lines = captor.getValue().getLines();

        assertLine(lines, AccountCategory.SALES_REVENUE, EntryType.CREDIT, new BigDecimal("100.00"));
        assertLine(lines, AccountCategory.DISCOUNT_EXPENSE, EntryType.DEBIT, new BigDecimal("20.00"));
        assertLine(lines, AccountCategory.CASH, EntryType.DEBIT, new BigDecimal("90.00"));
    }

    private WalkInOrder createWalkInOrder(WalkInPaymentMethod paymentMethod, BigDecimal splitCash, BigDecimal splitMobile) {
        return WalkInOrder.builder()
                .orderNumber("WLK-123")
                .processedBy(User.builder().fullName("Admin User").build())
                .paymentMethod(paymentMethod)
                .subtotal(new Money(new BigDecimal("100.00"), CurrencyCode.GHS))
                .discountAmount(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                .totalTaxAmount(new Money(new BigDecimal("10.00"), CurrencyCode.GHS))
                .totalAmount(new Money(new BigDecimal("110.00"), CurrencyCode.GHS))
                .amountPaid(new Money(new BigDecimal("110.00"), CurrencyCode.GHS))
                .changeGiven(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                .splitCashAmount(splitCash)
                .splitMobileAmount(splitMobile)
                .items(List.of(
                        WalkInOrderItem.builder()
                                .costPrice(new BigDecimal("50.00"))
                                .quantity(1)
                                .build()
                ))
                .createdAt(LocalDateTime.now())
                .build();
    }

    private LedgerAccount createAccount(Long id, AccountCategory category, AccountType type, BigDecimal balance) {
        return LedgerAccount.builder()
                .id(id)
                .code("ACC-" + id)
                .name(category.name())
                .category(category)
                .type(type)
                .balance(balance)
                .build();
    }

    private void assertLine(List<JournalEntryLine> lines, AccountCategory category, EntryType type, BigDecimal amount) {
        assertTrue(lines.stream().anyMatch(l ->
                l.getAccount().getCategory() == category
                        && l.getEntryType() == type
                        && l.getAmount().compareTo(amount) == 0
        ));
    }
}
