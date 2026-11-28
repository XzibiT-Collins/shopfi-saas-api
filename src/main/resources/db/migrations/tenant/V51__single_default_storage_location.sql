-- PLAN-07 (owner decision 2026-11-28: "The system should only create 1"): a tenant starts with ONE storage location.
-- V5 seeds 'Shop Floor' (SHOP_FLOOR, holds the default receiving, walk-in sale source and e-commerce fulfilment roles)
-- and 'Store Room 1' (STORE_ROOM, no role). Every location counts towards MAX_LOCATIONS (limit 1 on Starter and
-- Growth), so the second seed put every new store over its limit. V5 is applied everywhere and is never edited; this
-- migration removes the seeded store room again, for new tenants (V5 then V51 in one provisioning run) and for existing
-- ones alike, but ONLY while nothing references it:
--   * the row is still the seed: name 'Store Room 1', type STORE_ROOM, holding none of the three default roles;
--   * another location exists (a tenant is never left without a location);
--   * no row of ANY table with a foreign key to storage_locations points at it (location_stock rows of any quantity,
--     stock_transfers from / to, inventory_allocations.fulfilment_location_id, and whatever FK exists when this runs).
-- Otherwise the store room is kept (it is the tenant's data now). Re-runnable: once it is gone, or kept, a re-run changes
-- nothing (replay tests delete flyway_schema_history rows and run later migrations again).
--
-- Numbered V51, not V21: tenant V50 (user token_version) is applied on every existing tenant schema and Flyway runs with
-- outOfOrder=false, so a resolved V21 below the applied V50 would be "ignored" and fail validate-on-migrate at boot
-- ("Detected resolved migration not applied to database"). V51 is the next version above every applied one.
DO
$$
DECLARE
    room_id    BIGINT;
    fk         RECORD;
    referenced BOOLEAN;
BEGIN
    SELECT id
    INTO room_id
    FROM storage_locations
    WHERE name = 'Store Room 1'
      AND type = 'STORE_ROOM'
      AND NOT is_default_receiving
      AND NOT is_walk_in_sale_source
      AND NOT is_ecommerce_fulfilment_source;

    IF room_id IS NULL THEN
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM storage_locations WHERE id <> room_id) THEN
        RETURN;
    END IF;

    -- Every single-column foreign key that references storage_locations (in this schema: the regclass resolves through
    -- the search_path Flyway sets to the tenant schema).
    FOR fk IN
        SELECT c.conrelid::regclass AS referencing_table, a.attname AS referencing_column
        FROM pg_constraint c
                 JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        WHERE c.contype = 'f'
          AND c.confrelid = 'storage_locations'::regclass
        LOOP
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s WHERE %I = $1)', fk.referencing_table, fk.referencing_column)
                INTO referenced
                USING room_id;
            IF referenced THEN
                RETURN;
            END IF;
        END LOOP;

    DELETE FROM storage_locations WHERE id = room_id;
END
$$;
