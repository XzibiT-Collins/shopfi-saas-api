package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.accounts.request.JournalEntryLineRequest;
import com.example.perfume_budget.dto.accounts.request.ManualJournalEntryRequest;
import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.exception.AccountingException;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.model.*;
import com.example.perfume_budget.repository.JournalEntryRepository;
import com.example.perfume_budget.repository.LedgerAccountRepository;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookkeepingServiceTest {

    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private LedgerAccountRepository ledgerAccountRepository;
    @Mock
    private JournalEntryNumberGenerator entryNumberGenerator;
    @Mock
    private AuthUserUtil authUserUtil;

    @InjectMocks
    private BookkeepingService bookkeepingService;

    private LedgerAccount cashAccount;
    private LedgerAccount revenueAccount;
    private LedgerAccount taxPayableAccount;
    private LedgerAccount discountExpenseAccount;
    private LedgerAccount cogsAccount;
    private LedgerAccount inventoryAccount;
    private LedgerAccount inventoryDecreaseAccount;
    private LedgerAccount inventoryIncreaseAccount;
    private LedgerAccount accountsPayableAccount;

    @BeforeEach
    void setUp() {
        cashAccount = createAccount(1L, "1001", "Cash", AccountCategory.CASH, AccountType.ASSET, BigDecimal.ZERO);
        revenueAccount = createAccount(2L, "4001", "Sales Revenue", AccountCategory.SALES_REVENUE, AccountType.REVENUE, BigDecimal.ZERO);
        taxPayableAccount = createAccount(3L, "2001", "Tax Payable", AccountCategory.TAX_PAYABLE, AccountType.LIABILITY, BigDecimal.ZERO);
        discountExpenseAccount = createAccount(4L, "5001", "Discount Expense", AccountCategory.DISCOUNT_EXPENSE, AccountType.EXPENSE, BigDecimal.ZERO);
        cogsAccount = createAccount(5L, "5002", "COGS", AccountCategory.COGS, AccountType.EXPENSE, BigDecimal.ZERO);
        inventoryAccount = createAccount(6L, "1002", "Inventory", AccountCategory.INVENTORY, AccountType.ASSET, new BigDecimal("1000.00"));
        inventoryDecreaseAccount = createAccount(7L, "5100", "Inventory Decrease", AccountCategory.INVENTORY_DECREASE, AccountType.EXPENSE, BigDecimal.ZERO);
        inventoryIncreaseAccount = createAccount(8L, "5110", "Inventory Increase", AccountCategory.INVENTORY_INCREASE, AccountType.REVENUE, BigDecimal.ZERO);
        accountsPayableAccount = createAccount(9L, "2002", "Accounts Payable", AccountCategory.ACCOUNTS_PAYABLE, AccountType.LIABILITY, BigDecimal.ZERO);

        lenient().when(entryNumberGenerator.generate()).thenReturn("JE-001");
    }

    private LedgerAccount createAccount(Long id, String code, String name, AccountCategory category, AccountType type, BigDecimal balance) {
        return LedgerAccount.builder()
                .id(id)
                .code(code)
                .name(name)
                .category(category)
                .type(type)
                .balance(balance)
                .build();
    }

    @Test
    @DisplayName("Record Sale - Success without discount or tax")
    void recordSale_Success_Basic() {
        // Arrange
        Order order = createOrder("ORD-001", new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"));
        OrderItem item = OrderItem.builder()
                .costPrice(new Money(new BigDecimal("60.00"), CurrencyCode.GHS))
                .quantity(1)
                .build();
        order.setItems(List.of(item));

        Payment payment = Payment.builder().paidAt(LocalDateTime.now()).build();

        mockAccounts();

        // Act
        bookkeepingService.recordSale(order, payment);

        // Assert
        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(entryCaptor.capture());
        JournalEntry entry = entryCaptor.getValue();

        assertEquals(JournalEntryType.SALE, entry.getType());
        assertEquals("ORD-001", entry.getReferenceId());
        
        List<JournalEntryLine> lines = entry.getLines();
        assertEquals(4, lines.size()); // Cash (D), Revenue (C), COGS (D), Inventory (C)

        assertLine(lines, AccountCategory.CASH, EntryType.DEBIT, new BigDecimal("100.00"));
        assertLine(lines, AccountCategory.SALES_REVENUE, EntryType.CREDIT, new BigDecimal("100.00"));
        assertLine(lines, AccountCategory.COGS, EntryType.DEBIT, new BigDecimal("60.00"));
        assertLine(lines, AccountCategory.INVENTORY, EntryType.CREDIT, new BigDecimal("60.00"));
    }

    @Test
    @DisplayName("Record Sale - Success with discount and tax")
    void recordSale_Success_WithDiscountAndTax() {
        // Arrange
        // subtotal 100, discount 10, tax 5 -> total 95
        Order order = createOrder("ORD-002", new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("5.00"), new BigDecimal("95.00"));
        OrderItem item = OrderItem.builder()
                .costPrice(new Money(new BigDecimal("50.00"), CurrencyCode.GHS))
                .quantity(1)
                .build();
        order.setItems(List.of(item));

        Payment payment = Payment.builder().paidAt(LocalDateTime.now()).build();

        mockAccounts();

        // Act
        bookkeepingService.recordSale(order, payment);

        // Assert
        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(entryCaptor.capture());
        JournalEntry entry = entryCaptor.getValue();

        List<JournalEntryLine> lines = entry.getLines();
        
        assertLine(lines, AccountCategory.CASH, EntryType.DEBIT, new BigDecimal("95.00"));
        assertLine(lines, AccountCategory.SALES_REVENUE, EntryType.CREDIT, new BigDecimal("100.00"));
        assertLine(lines, AccountCategory.TAX_PAYABLE, EntryType.CREDIT, new BigDecimal("5.00"));
        assertLine(lines, AccountCategory.DISCOUNT_EXPENSE, EntryType.DEBIT, new BigDecimal("10.00"));
        
        // Assert no cash offset
        assertFalse(lines.stream().anyMatch(l -> 
            l.getAccount().getCategory() == AccountCategory.CASH && 
            l.getEntryType() == EntryType.CREDIT
        ), "Should not have cash offset for discount");
    }

    @Test
    @DisplayName("Record Sale - Automatic (product/shop) discount books gross revenue + discount line")
    void recordSale_Success_WithAutomaticDiscount() {
        // net subtotal 80, automatic discount 20 -> gross 100; no tax; total 80
        Order order = createOrder("ORD-AUTO", new BigDecimal("80.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("80.00"));
        order.setAutomaticDiscountAmount(new Money(new BigDecimal("20.00"), CurrencyCode.GHS));
        OrderItem item = OrderItem.builder()
                .costPrice(new Money(new BigDecimal("50.00"), CurrencyCode.GHS))
                .quantity(1)
                .build();
        order.setItems(List.of(item));
        Payment payment = Payment.builder().paidAt(LocalDateTime.now()).build();
        mockAccounts();

        bookkeepingService.recordSale(order, payment);

        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(entryCaptor.capture());
        List<JournalEntryLine> lines = entryCaptor.getValue().getLines();

        // revenue is GROSS (80 net + 20 automatic discount), discount expensed separately
        assertLine(lines, AccountCategory.SALES_REVENUE, EntryType.CREDIT, new BigDecimal("100.00"));
        assertLine(lines, AccountCategory.DISCOUNT_EXPENSE, EntryType.DEBIT, new BigDecimal("20.00"));
        assertLine(lines, AccountCategory.CASH, EntryType.DEBIT, new BigDecimal("80.00"));
        assertLine(lines, AccountCategory.COGS, EntryType.DEBIT, new BigDecimal("50.00"));
        assertLine(lines, AccountCategory.INVENTORY, EntryType.CREDIT, new BigDecimal("50.00"));
        // entry passed validateEntry => debits == credits (balanced)
    }

    @Test
    @DisplayName("Record Inventory Purchase - Success")
    void recordInventoryPurchase_Success() {
        // Arrange
        Product product = Product.builder()
                .id(1L)
                .name("Perfume X")
                .costPrice(new Money(new BigDecimal("50.00"), CurrencyCode.GHS))
                .build();
        User user = User.builder().fullName("Admin").build();
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(ledgerAccountRepository.findByCategory(AccountCategory.INVENTORY)).thenReturn(Optional.of(inventoryAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.ACCOUNTS_PAYABLE)).thenReturn(Optional.of(accountsPayableAccount));
        when(ledgerAccountRepository.findById(inventoryAccount.getId())).thenReturn(Optional.of(inventoryAccount));
        when(ledgerAccountRepository.findById(accountsPayableAccount.getId())).thenReturn(Optional.of(accountsPayableAccount));

        // Act
        BigDecimal initialBalance = inventoryAccount.getBalance();
        bookkeepingService.recordInventoryPurchase(product, 10);

        // Assert
        verify(journalEntryRepository).save(any(JournalEntry.class));
        assertEquals(initialBalance.add(new BigDecimal("500.00")), inventoryAccount.getBalance());
        assertEquals(new BigDecimal("500.00"), accountsPayableAccount.getBalance());
    }

    @Test
    @DisplayName("Record Inventory Decrease - Success")
    void recordInventoryAdjustment_Success() {
        // Arrange
        Product product = Product.builder().id(1L).name("Perfume X").build();
        User user = User.builder().fullName("Admin").build();
        BigDecimal initialBalance = inventoryAccount.getBalance();
        
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(ledgerAccountRepository.findByCategory(AccountCategory.INVENTORY_DECREASE)).thenReturn(Optional.of(inventoryDecreaseAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.INVENTORY)).thenReturn(Optional.of(inventoryAccount));
        when(ledgerAccountRepository.findById(inventoryDecreaseAccount.getId())).thenReturn(Optional.of(inventoryDecreaseAccount));
        when(ledgerAccountRepository.findById(inventoryAccount.getId())).thenReturn(Optional.of(inventoryAccount));

        // Act
        bookkeepingService.recordInventoryAdjustment(product, 5, new BigDecimal("250.00"));

        // Assert
        verify(journalEntryRepository).save(any(JournalEntry.class));
        assertEquals(new BigDecimal("250.00"), inventoryDecreaseAccount.getBalance());
        assertEquals(initialBalance.subtract(new BigDecimal("250.00")), inventoryAccount.getBalance());
    }

    @Test
    @DisplayName("Record Inventory Increase - Success")
    void recordInventoryAdjustmentIn_Success() {
        Product product = Product.builder().id(1L).name("Perfume X").build();
        User user = User.builder().fullName("Admin").build();
        BigDecimal initialBalance = inventoryAccount.getBalance();

        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(ledgerAccountRepository.findByCategory(AccountCategory.INVENTORY_INCREASE)).thenReturn(Optional.of(inventoryIncreaseAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.INVENTORY)).thenReturn(Optional.of(inventoryAccount));
        when(ledgerAccountRepository.findById(inventoryIncreaseAccount.getId())).thenReturn(Optional.of(inventoryIncreaseAccount));
        when(ledgerAccountRepository.findById(inventoryAccount.getId())).thenReturn(Optional.of(inventoryAccount));

        bookkeepingService.recordInventoryAdjustmentIn(product, 5, new BigDecimal("250.00"));

        verify(journalEntryRepository).save(any(JournalEntry.class));
        assertEquals(initialBalance.add(new BigDecimal("250.00")), inventoryAccount.getBalance());
        assertEquals(new BigDecimal("250.00"), inventoryIncreaseAccount.getBalance());
    }

    @Test
    @DisplayName("Record Manual Entry - Success")
    void recordManualEntry_Success() {
        // Arrange
        JournalEntryLineRequest line1 = new JournalEntryLineRequest(AccountCategory.CASH, EntryType.DEBIT, new BigDecimal("100.00"), "Add cash");
        JournalEntryLineRequest line2 = new JournalEntryLineRequest(AccountCategory.SALES_REVENUE, EntryType.CREDIT, new BigDecimal("100.00"), "Manual sale");
        ManualJournalEntryRequest request = new ManualJournalEntryRequest("Manual adjustment", JournalEntryType.ADJUSTMENT, List.of(line1, line2));

        User user = User.builder().fullName("Admin").build();
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(ledgerAccountRepository.findByCategory(AccountCategory.CASH)).thenReturn(Optional.of(cashAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.SALES_REVENUE)).thenReturn(Optional.of(revenueAccount));
        when(ledgerAccountRepository.findById(cashAccount.getId())).thenReturn(Optional.of(cashAccount));
        when(ledgerAccountRepository.findById(revenueAccount.getId())).thenReturn(Optional.of(revenueAccount));

        // Act
        bookkeepingService.recordManualEntry(request);

        // Assert
        verify(journalEntryRepository).save(any(JournalEntry.class));
        assertEquals(new BigDecimal("100.00"), cashAccount.getBalance());
        assertEquals(new BigDecimal("100.00"), revenueAccount.getBalance());
    }

    @Test
    @DisplayName("Record Manual Entry - Unbalanced throws BadRequestException")
    void recordManualEntry_Unbalanced_ThrowsException() {
        // Arrange
        JournalEntryLineRequest line1 = new JournalEntryLineRequest(AccountCategory.CASH, EntryType.DEBIT, new BigDecimal("100.00"), "Add cash");
        JournalEntryLineRequest line2 = new JournalEntryLineRequest(AccountCategory.SALES_REVENUE, EntryType.CREDIT, new BigDecimal("90.00"), "Manual sale");
        ManualJournalEntryRequest request = new ManualJournalEntryRequest("Manual adjustment", JournalEntryType.ADJUSTMENT, List.of(line1, line2));

        User user = User.builder().fullName("Admin").build();
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(ledgerAccountRepository.findByCategory(AccountCategory.CASH)).thenReturn(Optional.of(cashAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.SALES_REVENUE)).thenReturn(Optional.of(revenueAccount));

        // Act & Assert
        assertThrows(BadRequestException.class, () -> bookkeepingService.recordManualEntry(request));
    }

    @Test
    @DisplayName("Update Balance - Negative Balance on Critical Account throws exception")
    void updateBalance_NegativeCriticalAccount_ThrowsException() {
        // Arrange
        cashAccount.setBalance(new BigDecimal("50.00"));
        
        // Let's use Manual Entry to make it easier to test specific accounts
        JournalEntryLineRequest line1 = new JournalEntryLineRequest(AccountCategory.CASH, EntryType.CREDIT, new BigDecimal("100.00"), "Spend too much");
        JournalEntryLineRequest line2 = new JournalEntryLineRequest(AccountCategory.SALES_REVENUE, EntryType.DEBIT, new BigDecimal("100.00"), "Offset");
        ManualJournalEntryRequest request = new ManualJournalEntryRequest("Manual adjustment", JournalEntryType.ADJUSTMENT, List.of(line1, line2));

        User user = User.builder().fullName("Admin").build();
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(ledgerAccountRepository.findByCategory(AccountCategory.CASH)).thenReturn(Optional.of(cashAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.SALES_REVENUE)).thenReturn(Optional.of(revenueAccount));
        
        lenient().when(ledgerAccountRepository.findById(cashAccount.getId())).thenReturn(Optional.of(cashAccount));
        lenient().when(ledgerAccountRepository.findById(revenueAccount.getId())).thenReturn(Optional.of(revenueAccount));

        // Act & Assert
        assertThrows(BadRequestException.class, () -> bookkeepingService.recordManualEntry(request));
    }

    @Test
    @DisplayName("Record Sale - Unbalanced system entry throws AccountingException")
    void recordSale_Unbalanced_ThrowsAccountingException() {
        // Arrange
        // Total 95 but we'll try to mess with the numbers
        Order order = createOrder("ORD-003", new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("5.00"), new BigDecimal("100.00")); // total doesn't match
        Payment payment = Payment.builder().paidAt(LocalDateTime.now()).build();
        mockAccounts();

        // Act & Assert
        // total (100) + discount (10) != subtotal (100) + tax (5)
        // 110 != 105
        assertThrows(AccountingException.class, () -> bookkeepingService.recordSale(order, payment));
    }

    private Order createOrder(String number, BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal total) {
        return Order.builder()
                .orderNumber(number)
                .subtotal(new Money(subtotal, CurrencyCode.GHS))
                .discountAmount(new Money(discount, CurrencyCode.GHS))
                .totalTaxAmount(new Money(tax, CurrencyCode.GHS))
                .totalAmount(new Money(total, CurrencyCode.GHS))
                .items(Collections.emptyList())
                .build();
    }

    private void mockAccounts() {
        when(ledgerAccountRepository.findByCategory(AccountCategory.CASH)).thenReturn(Optional.of(cashAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.SALES_REVENUE)).thenReturn(Optional.of(revenueAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.TAX_PAYABLE)).thenReturn(Optional.of(taxPayableAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.DISCOUNT_EXPENSE)).thenReturn(Optional.of(discountExpenseAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.COGS)).thenReturn(Optional.of(cogsAccount));
        when(ledgerAccountRepository.findByCategory(AccountCategory.INVENTORY)).thenReturn(Optional.of(inventoryAccount));

        lenient().when(ledgerAccountRepository.findById(cashAccount.getId())).thenReturn(Optional.of(cashAccount));
        lenient().when(ledgerAccountRepository.findById(revenueAccount.getId())).thenReturn(Optional.of(revenueAccount));
        lenient().when(ledgerAccountRepository.findById(taxPayableAccount.getId())).thenReturn(Optional.of(taxPayableAccount));
        lenient().when(ledgerAccountRepository.findById(discountExpenseAccount.getId())).thenReturn(Optional.of(discountExpenseAccount));
        lenient().when(ledgerAccountRepository.findById(cogsAccount.getId())).thenReturn(Optional.of(cogsAccount));
        lenient().when(ledgerAccountRepository.findById(inventoryAccount.getId())).thenReturn(Optional.of(inventoryAccount));
    }

    private void assertLine(List<JournalEntryLine> lines, AccountCategory category, EntryType type, BigDecimal amount) {
        assertTrue(lines.stream().anyMatch(l -> 
            l.getAccount().getCategory() == category && 
            l.getEntryType() == type && 
            l.getAmount().compareTo(amount) == 0
        ), "Line not found: " + category + " " + type + " " + amount);
    }
}
