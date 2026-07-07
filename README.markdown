# Perfume Budget - E-Commerce & Accounting System

A robust, enterprise-grade backend system for a perfume e-commerce platform, featuring integrated double-entry bookkeeping, automated tax calculations, and real-time financial reporting.

## 📌 Project Overview

Perfume Budget is not just an e-commerce platform; it's a complete business management tool. It bridges the gap between sales and accounting by automatically recording every transaction into a digital ledger. When a customer buys a perfume, the system doesn't just manage the stock—it records the revenue, calculates taxes, tracks the cost of goods sold (COGS), and updates the cash balance.

## 🚀 Key Features

### 🛒 E-Commerce Core
- **Product Management:** Full CRUD for products with stock tracking and category management.
- **Cart & Checkout:** Dynamic cart management with integrated coupon validation.
- **Order Management:** Automated order numbering, status tracking (PENDING to DELIVERED), and stock reservation/release logic.
- **Payment Integration:** Secure payment processing via **Paystack**, including robust webhook handling for transaction verification.

### ⚖️ Advanced Accounting (The "Brain")
- **Double-Entry Bookkeeping:** Automated recording of all business events (Sales, Purchases, Adjustments) into Journal Entries.
- **Ledger Integrity:** "Air-tight" validation ensures every transaction is balanced (Debits = Credits) before hitting the database.
- **Balance Protection:** Built-in safeguards prevent critical accounts (Cash, Inventory) from going negative.
- **Manual Journal Entries:** Capability for admins to record adjustments or external expenses directly.

### 📊 Financial Reporting
- **Income Statement:** Real-time calculation of Gross Profit and Net Profit over any date range.
- **Balance Sheet:** Snapshot of Assets, Liabilities, and Equity.
- **Cash Flow Statement:** Daily tracking of inflows and outflows.
- **Audit Trail:** Comprehensive journal entry logs with reference tracing (link any entry back to its specific Order or Product).

### 🛠 Technical Features
- **Tax Engine:** Dynamic tax calculation based on configurable tax rules.
- **Async Processing:** Event-driven updates for emails and metrics to keep the user experience snappy.
- **Cloud Integration:** Image management via **Cloudinary**.
- **Security:** JWT-based authentication with role-based access control (RBAC).

## 🏗 Architecture

The project follows a clean, layered architecture:

- **Controller Layer:** REST endpoints following standard naming conventions (`AccountingController`, `OrderController`, etc.).
- **Service Layer:** Business logic encapsulated in interfaces and implementations (`AccountingReportServiceImpl`, `BookkeepingService`).
- **Repository Layer:** Spring Data JPA for database abstraction, including advanced JPQL queries for financial aggregation.
- **DTO Layer:** Strict separation between database models and API contracts using Java Records.
- **Event System:** Internal Spring events (`OrderStatusChangeEvent`) for decoupled logic execution.

## 🛠 Tech Stack

- **Framework:** Spring Boot 3.5.x
- **Language:** Java 21
- **Persistence:** Hibernate / Spring Data JPA
- **Database:** H2 (Test/Dev) / PostgreSQL (Production)
- **Cache:** Redis
- **Security:** Spring Security & JWT
- **Utilities:** Lombok, MapStruct (for DTO mapping), Mockito (Testing)

## 🚦 Getting Started

### Prerequisites
- JDK 21+
- Maven 3.9+
- Redis (optional for local dev, but required for caching features)

### Installation
1. **Clone the repository:**
   ```bash
   git clone https://github.com/your-repo/perfume-budget.git
   cd perfume_budget
   ```

2. **Configure Environment:**
   Update `src/main/resources/application.properties` with your credentials:
   - `CLOUDINARY_URL`
   - `PAYSTACK_SECRET_KEY`
   - `REDIS_HOST` & `REDIS_PORT`

3. **Build the project:**
   ```bash
   ./mvnw clean install
   ```

4. **Run the application:**
   ```bash
   ./mvnw spring-boot:run
   ```

## 🧪 Testing

The application maintains high code quality with a focus on logic integrity.

- **Run all tests:**
  ```bash
  ./mvnw test
  ```
- **Key Test Suites:**
  - `BookkeepingServiceTest`: Validates ledger logic and balance protection.
  - `AccountingReportServiceImplTest`: Verifies financial calculation accuracy.
  - `OrderServiceImplTest`: Tests checkout flows and service integrations.

## 📖 API Documentation

The system provides a metadata endpoint for frontend developers to dynamically build forms:
- `GET /api/v1/admin/accounting/metadata`: Returns all `AccountCategory` and `JournalEntryType` values with formatted labels.

Full endpoint documentation can be found in [API_ENDPOINTS.markdown](/API_ENDPOINTS.markdown).

## 🤝 Contribution Guidelines

1. **Service Logic:** Always ensure any new service that affects value (Sales, Refunds, Expenses) integrates with `BookkeepingService`.
2. **Testing:** New features **must** include comprehensive tests covering both success paths and edge cases (e.g., unbalanced entries).
3. **DTOs:** Do not expose Entities. Always use Records for DTOs.
