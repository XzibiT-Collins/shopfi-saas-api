CREATE INDEX idx_inventory_alloc_status_created
    ON inventory_allocations (status, created_at);
