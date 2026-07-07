-- Automatic (product-specific + shop-wide) discount baked into the effective subtotal.
-- Lets the journal record gross revenue + a discount-expense line for these discounts,
-- consistent with how coupons/manual discounts are already booked.
ALTER TABLE orders
    ADD COLUMN automatic_discount_amount   DECIMAL(19, 2),
    ADD COLUMN automatic_discount_currency VARCHAR(3);

ALTER TABLE walk_in_orders
    ADD COLUMN automatic_discount_amount   DECIMAL(19, 2),
    ADD COLUMN automatic_discount_currency VARCHAR(3);
