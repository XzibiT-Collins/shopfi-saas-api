package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.accounts.request.ManualJournalEntryRequest;
import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.AccountType;
import com.example.perfume_budget.enums.EntryType;
import com.example.perfume_budget.enums.JournalEntryType;
import com.example.perfume_budget.enums.WalkInPaymentMethod;
import com.example.perfume_budget.exception.AccountingException;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.model.*;
import com.example.perfume_budget.repository.JournalEntryRepository;
import com.example.perfume_budget.repository.LedgerAccountRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.JournalEntryNumberGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookkeepingService {
    private final JournalEntryRepository journalEntryRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final JournalEntryNumberGenerator entryNumberGenerator;
    private final AuthUserUtil authUserUtil;
    private final ProductRepository productRepository;

    @Transactional
    public void recordSale(Order order, Payment payment){
        LedgerAccount cash = getAccount(AccountCategory.CASH, false);
        LedgerAccount revenue = getAccount(AccountCategory.SALES_REVENUE, false);
        LedgerAccount taxPayable = getAccount(AccountCategory.TAX_PAYABLE, false);
        LedgerAccount discountExpense = getAccount(AccountCategory.DISCOUNT_EXPENSE, false);
        LedgerAccount cogs = getAccount(AccountCategory.COGS, false);
        LedgerAccount inventory = getAccount(AccountCategory.INVENTORY, false);

        BigDecimal subtotal = moneyAmount(order.getSubtotal());          // net of automatic discount
        BigDecimal autoDiscount = moneyAmount(order.getAutomaticDiscountAmount()); // product/shop
        BigDecimal couponDiscount = moneyAmount(order.getDiscountAmount());        // coupon
        BigDecimal grossRevenue = subtotal.add(autoDiscount);
        BigDecimal tax = order.getTotalTaxAmount().getAmount();
        BigDecimal total = order.getTotalAmount().getAmount();
        BigDecimal totalCOGS = order.getItems().stream()
                .map(item -> item.getCostPrice().getAmount()
                        .multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);


        JournalEntry entry = JournalEntry.builder()
                .entryNumber(entryNumberGenerator.generate())
                .description("Sale recorded for order: " + order.getOrderNumber())
                .type(JournalEntryType.SALE)
                .referenceType("ORDER")
                .referenceId(order.getOrderNumber())
                .isManual(false)
                .recordedBy("System")
                .transactionDate(payment.getPaidAt())
                .build();

        List<JournalEntryLine> lines = new ArrayList<>();
        // cash received
        lines.add(buildLines(entry, cash, EntryType.DEBIT, total, "Payment Received"));
        // revenue earned (Gross — before any discount)
        lines.add(buildLines(entry, revenue, EntryType.CREDIT, grossRevenue, "Sales revenue"));

        // tax collected
        if(tax.compareTo(BigDecimal.ZERO) > 0){
            lines.add(buildLines(entry, taxPayable, EntryType.CREDIT, tax, "Tax collected"));
        }

        // discount given — separate lines per source for audit detail
        if(autoDiscount.compareTo(BigDecimal.ZERO) > 0){
            lines.add(buildLines(entry, discountExpense, EntryType.DEBIT, autoDiscount, "Automatic discount (product/shop) for order: " + order.getOrderNumber()));
        }
        if(couponDiscount.compareTo(BigDecimal.ZERO) > 0){
            lines.add(buildLines(entry, discountExpense, EntryType.DEBIT, couponDiscount, "Coupon discount for order: " + order.getOrderNumber()));
        }

        // record cost of goods sold and inventory reduction
        if(totalCOGS.compareTo(BigDecimal.ZERO) > 0){
            lines.add(buildLines(entry, cogs, EntryType.DEBIT, totalCOGS, "Cost of goods sold for order: " + order.getOrderNumber()));
            lines.add(buildLines(entry, inventory, EntryType.CREDIT, totalCOGS, "Inventory consumed for order: " + order.getOrderNumber()));
        }


        entry.setLines(lines);
        validateEntry(lines, false);
        journalEntryRepository.save(entry);
        updateAccountBalances(lines, false);

        log.info("Journal entry recorded for order: {}", order.getOrderNumber());
    }

    @Transactional
    public void recordWalkInSale(WalkInOrder order) {
        LedgerAccount cash = getAccount(AccountCategory.CASH, false);
        LedgerAccount mobileMoney = getAccount(AccountCategory.MOBILE_MONEY, false);
        LedgerAccount revenue = getAccount(AccountCategory.SALES_REVENUE, false);
        LedgerAccount taxPayable = getAccount(AccountCategory.TAX_PAYABLE, false);
        LedgerAccount discountExpense = getAccount(AccountCategory.DISCOUNT_EXPENSE, false);
        LedgerAccount cogs = getAccount(AccountCategory.COGS, false);
        LedgerAccount inventory = getAccount(AccountCategory.INVENTORY, false);

        BigDecimal subtotal = moneyAmount(order.getSubtotal());          // net of automatic discount
        BigDecimal autoDiscount = moneyAmount(order.getAutomaticDiscountAmount()); // product/shop
        BigDecimal manualDiscount = moneyAmount(order.getDiscountAmount());        // manual front-desk
        BigDecimal grossRevenue = subtotal.add(autoDiscount);
        BigDecimal tax = order.getTotalTaxAmount().getAmount();
        BigDecimal total = order.getTotalAmount().getAmount();
        BigDecimal totalCOGS = order.getItems().stream()
                .map(item -> item.getCostPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        JournalEntry entry = JournalEntry.builder()
                .entryNumber(entryNumberGenerator.generate())
                .description("Walk-in sale recorded for order: " + order.getOrderNumber()
                        + " | Admin: " + order.getProcessedBy().getFullName()
                        + " | Payment: " + order.getPaymentMethod())
                .type(JournalEntryType.WALK_IN_SALE)
                .referenceType("WALK_IN_ORDER")
                .referenceId(order.getOrderNumber())
                .isManual(false)
                .recordedBy(order.getProcessedBy().getFullName())
                .transactionDate(order.getCreatedAt() != null ? order.getCreatedAt() : LocalDateTime.now())
                .build();

        List<JournalEntryLine> lines = new ArrayList<>();
        addWalkInPaymentLines(order, entry, lines, cash, mobileMoney, total);
        lines.add(buildLines(entry, revenue, EntryType.CREDIT, grossRevenue, "Sales revenue"));

        if (tax.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(buildLines(entry, taxPayable, EntryType.CREDIT, tax, "Tax collected"));
        }

        // discount given — separate lines per source for audit detail
        if (autoDiscount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(buildLines(entry, discountExpense, EntryType.DEBIT, autoDiscount, "Automatic discount (product/shop) for order: " + order.getOrderNumber()));
        }
        if (manualDiscount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(buildLines(entry, discountExpense, EntryType.DEBIT, manualDiscount, "Manual discount for order: " + order.getOrderNumber()));
        }

        if (totalCOGS.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(buildLines(entry, cogs, EntryType.DEBIT, totalCOGS, "Cost of goods sold for walk-in order: " + order.getOrderNumber()));
            lines.add(buildLines(entry, inventory, EntryType.CREDIT, totalCOGS, "Inventory consumed for walk-in order: " + order.getOrderNumber()));
        }

        entry.setLines(lines);
        validateEntry(lines, false);
        journalEntryRepository.save(entry);
        updateAccountBalances(lines, false);

        log.info("Walk-in journal entry recorded for order: {}", order.getOrderNumber());
    }

    /** Null-safe amount accessor (older orders may have no automatic-discount value). */
    private static BigDecimal moneyAmount(Money money) {
        return money != null && money.getAmount() != null ? money.getAmount() : BigDecimal.ZERO;
    }


    @Transactional
    public void recordInventoryPurchase(Product product, int quantity){
        BigDecimal inventoryValue = product.getCostPrice().getAmount().multiply(BigDecimal.valueOf(quantity));
        recordInventoryPurchase(product, quantity, inventoryValue);
    }

    @Transactional
    public void recordInventoryPurchase(Product product, int quantity, BigDecimal inventoryValue){

        LedgerAccount inventory = getAccount(AccountCategory.INVENTORY, true);
        LedgerAccount accountsPayable = getAccount(AccountCategory.ACCOUNTS_PAYABLE, true);
        User currentUser = authUserUtil.getCurrentUser();
        JournalEntry entry = JournalEntry.builder()
                .entryNumber(entryNumberGenerator.generate())
                .description("Inventory purchase - "+ product.getName())
                .type(JournalEntryType.INVENTORY_PURCHASE)
                .referenceId(product.getId().toString())
                .referenceType("PRODUCT")
                .isManual(false)
                .recordedBy(currentUser.getFullName())
                .transactionDate(LocalDateTime.now())
                .build();

        List<JournalEntryLine> lines = new ArrayList<>();
        lines.add(buildLines(entry, inventory,EntryType.DEBIT, inventoryValue, "Inventory acquired - "+ product.getName()));
        lines.add(buildLines(entry, accountsPayable, EntryType.CREDIT, inventoryValue, "Payable for inventory purchase"));

        entry.setLines(lines);
        validateEntry(lines, true);
        journalEntryRepository.save(entry);
        updateAccountBalances(lines, true);
    }


    @Transactional
    public void recordInventoryAdjustment(Product product, int quantity, BigDecimal adjustmentValue){
        LedgerAccount inventoryDecrease = getAccount(AccountCategory.INVENTORY_DECREASE, true);
        LedgerAccount inventory = getAccount(AccountCategory.INVENTORY, true);

        JournalEntry entry = JournalEntry.builder()
                .entryNumber(entryNumberGenerator.generate())
                .description("Inventory decrease - "+ product.getName())
                .type(JournalEntryType.INVENTORY_DECREASE)
                .referenceId(product.getId().toString())
                .referenceType("PRODUCT")
                .isManual(false)
                .recordedBy(authUserUtil.getCurrentUser().getFullName())
                .transactionDate(LocalDateTime.now())
                .build();

        List<JournalEntryLine> lines = new ArrayList<>();
        lines.add(buildLines(entry, inventoryDecrease, EntryType.DEBIT, adjustmentValue, "Inventory decrease - " + product.getName()));
        lines.add(buildLines(entry, inventory, EntryType.CREDIT, adjustmentValue, "Inventory removed from books"));

        entry.setLines(lines);
        validateEntry(lines, true);
        journalEntryRepository.save(entry);
        updateAccountBalances(lines, true);

        log.info("Inventory decrease recorded for product: {} - {} units removed", product.getName(), quantity);
    }

    @Transactional
    public void recordInventoryAdjustmentIn(Product product, int quantity, BigDecimal adjustmentValue){
        LedgerAccount inventoryIncrease = getAccount(AccountCategory.INVENTORY_INCREASE, true);
        LedgerAccount inventory = getAccount(AccountCategory.INVENTORY, true);

        JournalEntry entry = JournalEntry.builder()
                .entryNumber(entryNumberGenerator.generate())
                .description("Inventory increase - "+ product.getName())
                .type(JournalEntryType.INVENTORY_INCREASE)
                .referenceId(product.getId().toString())
                .referenceType("PRODUCT")
                .isManual(false)
                .recordedBy(authUserUtil.getCurrentUser().getFullName())
                .transactionDate(LocalDateTime.now())
                .build();

        List<JournalEntryLine> lines = new ArrayList<>();
        lines.add(buildLines(entry, inventory, EntryType.DEBIT, adjustmentValue, "Inventory increase - " + product.getName()));
        lines.add(buildLines(entry, inventoryIncrease, EntryType.CREDIT, adjustmentValue, "Inventory increase recognized"));

        entry.setLines(lines);
        validateEntry(lines, true);
        journalEntryRepository.save(entry);
        updateAccountBalances(lines, true);

        log.info("Inventory increase recorded for product: {} - {} units added", product.getName(), quantity);
    }

    @Transactional
    public void recordStockConversion(StockConversion conversion) {
        LedgerAccount inventory = getAccount(AccountCategory.INVENTORY, false);
        
        JournalEntry entry = JournalEntry.builder()
                .entryNumber(entryNumberGenerator.generate())
                .description("Stock Conversion: " + conversion.getConversionNumber())
                .type(JournalEntryType.ADJUSTMENT)
                .referenceId(conversion.getConversionNumber())
                .referenceType("CONVERSION")
                .isManual(false)
                .recordedBy(conversion.getConvertedBy().getFullName())
                .transactionDate(LocalDateTime.now())
                .build();

        List<JournalEntryLine> lines = new ArrayList<>();
        
        // Incoming inventory
        lines.add(buildLines(entry, inventory, EntryType.DEBIT, conversion.getToCostValue(), 
            "Inventory acquired from conversion to " + conversion.getToProduct().getName()));

        // Outgoing inventory
        lines.add(buildLines(entry, inventory, EntryType.CREDIT, conversion.getFromCostValue(), 
            "Inventory consumed for conversion from " + conversion.getFromProduct().getName()));

        BigDecimal variance = conversion.getVarianceAmount();
        if (variance != null && variance.compareTo(BigDecimal.ZERO) != 0) {
            LedgerAccount varianceAccount = getAccount(AccountCategory.INVENTORY_VARIANCE, false);
            if (variance.compareTo(BigDecimal.ZERO) > 0) {
                // If variance is positive, it means fromCostValue > toCostValue (e.g., Credit 144, Debit 120 -> need Debit 24)
                // We debit the variance account (loss of value)
                lines.add(buildLines(entry, varianceAccount, EntryType.DEBIT, variance, "Inventory variance from conversion"));
            } else {
                // If variance is negative, it means toCostValue > fromCostValue (e.g., Credit 120, Debit 144 -> need Credit 24)
                // We credit the variance account (gain of value)
                lines.add(buildLines(entry, varianceAccount, EntryType.CREDIT, variance.abs(), "Inventory variance from conversion"));
            }
        }

        entry.setLines(lines);
        validateEntry(lines, false);
        journalEntryRepository.save(entry);
        updateAccountBalances(lines, false);

        log.info("Journal entry recorded for stock conversion: {}", conversion.getConversionNumber());
    }


    @Transactional
    public void recordManualEntry(ManualJournalEntryRequest request){
        User user = authUserUtil.getCurrentUser();
        JournalEntry entry = JournalEntry.builder()
                .entryNumber(entryNumberGenerator.generate())
                .description(request.description())
                .type(request.type())
                .referenceType("MANUAL")
                .referenceId("MANUAL - " + user.getFullName())
                .transactionDate(LocalDateTime.now())
                .isManual(true)
                .recordedBy(user.getFullName())
                .build();

        List<JournalEntryLine> lines = request.lines().stream()
                .map(lineRequest -> {
                    LedgerAccount account = ledgerAccountRepository.findByCategory(lineRequest.accountCategory())
                            .orElseThrow(() -> new BadRequestException("Account not found for category: " + lineRequest.accountCategory()));

                    return buildLines(entry, account, lineRequest.entryType(), lineRequest.amount(), lineRequest.description());
                }).toList();

        entry.setLines(lines);
        validateEntry(lines, true);
        journalEntryRepository.save(entry);
        updateAccountBalances(lines, true);

        log.info("Manual journal entry recorded by user: {} - {}", user.getFullName(), entry.getEntryNumber());
    }

    @Transactional
    public void recordInventoryRevaluation(ProductFamily family, BigDecimal oldCostPrice, BigDecimal newCostPrice){
        List<Product> variants = productRepository.findByFamily(family);

        BigDecimal totalOldValue = variants.stream()
                .map(v -> oldCostPrice
                        .multiply(BigDecimal.valueOf(v.getConversionFactor()))
                        .multiply(BigDecimal.valueOf(v.getStockQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalNewValue = variants.stream()
                .map(v -> newCostPrice
                        .multiply(BigDecimal.valueOf(v.getConversionFactor()))
                        .multiply(BigDecimal.valueOf(v.getStockQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal variance = totalNewValue.subtract(totalOldValue);

        if(variance.compareTo(BigDecimal.ZERO) == 0) return;

        LedgerAccount inventory = getAccount(AccountCategory.INVENTORY, false);
        LedgerAccount revaluation = getAccount(AccountCategory.INVENTORY_REVALUATION, false);

        boolean isIncrease = variance.compareTo(BigDecimal.ZERO) > 0;
        BigDecimal absoluteVariance = variance.abs();

        JournalEntry entry = JournalEntry.builder()
                .entryNumber(entryNumberGenerator.generate())
                .description("Inventory revaluation for family: "
                        + family.getName()
                        + " | Cost price "
                        + (isIncrease ? "increased" : "decreased")
                        + " from GHS " + oldCostPrice
                        + " to GHS " + newCostPrice)
                .type(JournalEntryType.INVENTORY_REVALUATION)
                .referenceType("PRODUCT_FAMILY")
                .referenceId(family.getFamilyCode())
                .transactionDate(LocalDateTime.now())
                .isManual(false)
                .recordedBy("System")
                .build();

        List<JournalEntryLine> lines = new ArrayList<>();
        if(isIncrease){
            lines.add(buildLines(entry, inventory, EntryType.DEBIT, absoluteVariance, "Inventory revaluation gain"));
            lines.add(buildLines(entry, revaluation, EntryType.CREDIT, absoluteVariance, "Revaluation reserve increase"));
        }else{
            lines.add(buildLines(entry, revaluation, EntryType.DEBIT, absoluteVariance, "Revaluation reserve decrease"));
            lines.add(buildLines(entry, inventory, EntryType.CREDIT, absoluteVariance, "Inventory revaluation loss"));
        }

        entry.setLines(lines);
        validateEntry(lines, false);
        journalEntryRepository.save(entry);
        updateAccountBalances(lines, false);
        log.info("Inventory revaluation recorded - {} by GHS {}", isIncrease ? "increased" : "decreased", absoluteVariance);
    }

    private void updateAccountBalances(List<JournalEntryLine> lines, boolean isManual) {
        lines.forEach(line -> {
            LedgerAccount account = ledgerAccountRepository.findById(line.getAccount().getId())
                    .orElseThrow(() -> isManual ? new BadRequestException(line.getAccount().getCode() + " Account not found") : new AccountingException("Account not found for code: " + line.getAccount().getCode()));

            // Assets and Expenses have normal DEBIT balances
            // Liabilities, Equity and Revenue have normal CREDIT balances
            boolean isDebitNormal = account.getType() == AccountType.ASSET
                    || account.getType() == AccountType.EXPENSE;

            if (isDebitNormal) {
                // for debit-normal accounts: debit increases, credit decreases
                if (line.getEntryType() == EntryType.DEBIT) {
                    account.setBalance(account.getBalance().add(line.getAmount()));
                } else {
                    account.setBalance(account.getBalance().subtract(line.getAmount()));
                }
            } else {
                // for credit-normal accounts: credit increases, debit decreases
                if (line.getEntryType() == EntryType.CREDIT) {
                    account.setBalance(account.getBalance().add(line.getAmount()));
                } else {
                    account.setBalance(account.getBalance().subtract(line.getAmount()));
                }
            }

            if(account.getBalance().compareTo(BigDecimal.ZERO) < 0){
                boolean isCriticalAccount = account.getCategory() == AccountCategory.CASH
                        || account.getCategory() == AccountCategory.MOBILE_MONEY
                        || account.getCategory() == AccountCategory.INVENTORY;
                if(isManual || isCriticalAccount){
                    throw new BadRequestException(
                            account.getName() + " cannot go negative. " +
                                    "Current balance: " + account.getBalance() +
                                    ", attempted deduction: " + line.getAmount()
                    );
                }else{
                    log.warn("LEDGER ALERT: Account {} ({}) has gone negative: {}. " +
                                    "Reference: {}",
                            account.getCode(), account.getName(),
                            account.getBalance(), line.getJournalEntry().getReferenceId());
                }
            }
            ledgerAccountRepository.save(account);
        });
    }

    private void validateEntry(List<JournalEntryLine> lines, Boolean isManual) {
        BigDecimal totalDebits = lines.stream()
                .filter(line -> line.getEntryType() ==EntryType.DEBIT)
                .map(JournalEntryLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = lines.stream()
                .filter(line -> line.getEntryType() == EntryType.CREDIT)
                .map(JournalEntryLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if(totalDebits.compareTo(totalCredits) != 0){
            String message = "Journal entry is unbalanced. Debits: " + totalDebits + " Credits: " + totalCredits;
            if(Boolean.TRUE.equals(isManual)){
                throw new BadRequestException(message);
            }else{
                log.error("CRITICAL LEDGER ERROR: {}", message);
                throw new AccountingException(message);
            }
        }
    }


    private JournalEntryLine buildLines(JournalEntry entry, LedgerAccount account, EntryType entryType, BigDecimal amount, String description) {
        return JournalEntryLine.builder()
                .journalEntry(entry)
                .account(account)
                .entryType(entryType)
                .amount(amount)
                .description(description)
                .build();
    }


    private LedgerAccount getAccount(AccountCategory accountCategory, Boolean isManual) {
        return ledgerAccountRepository.findByCategory(accountCategory)
                .orElseThrow(() -> Boolean.TRUE.equals(isManual) ? new BadRequestException(accountCategory + " Account not found") : new AccountingException("Account not found for category: " + accountCategory));
    }

    private void addWalkInPaymentLines(WalkInOrder order,
                                       JournalEntry entry,
                                       List<JournalEntryLine> lines,
                                       LedgerAccount cash,
                                       LedgerAccount mobileMoney,
                                       BigDecimal total) {
        WalkInPaymentMethod paymentMethod = order.getPaymentMethod();

        switch (paymentMethod) {
            case CASH, CARD -> lines.add(buildLines(entry, cash, EntryType.DEBIT, total, "Walk-in payment received"));
            case MOBILE_MONEY -> lines.add(buildLines(entry, mobileMoney, EntryType.DEBIT, total, "Walk-in mobile money received"));
            case SPLIT -> {
                if (order.getSplitCashAmount() != null && order.getSplitCashAmount().compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(buildLines(entry, cash, EntryType.DEBIT, order.getSplitCashAmount(), "Walk-in split cash received"));
                }
                if (order.getSplitMobileAmount() != null && order.getSplitMobileAmount().compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(buildLines(entry, mobileMoney, EntryType.DEBIT, order.getSplitMobileAmount(), "Walk-in split mobile money received"));
                }
            }
        }
    }
}
