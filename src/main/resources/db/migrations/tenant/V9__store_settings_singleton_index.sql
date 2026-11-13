-- THEME-02b: enforce the singleton store_settings row at the database level. The application self-heals a missing
-- row under an advisory lock; this index is the backstop that makes a second row impossible.
-- Defensive de-duplication first (keeps the lowest id) so the index can always be created.
-- Note: V8 stamped created_at/updated_at with now() cast to TIMESTAMP WITHOUT TIME ZONE (session time zone). V8 is
-- not edited (applied checksums); application writes always use UTC explicitly (ThemeStore).
DELETE FROM store_settings a
    USING store_settings b
WHERE a.id > b.id;

CREATE UNIQUE INDEX uq_store_settings_singleton ON store_settings ((TRUE));
