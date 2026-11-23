-- ACCT-AUTH-BE (API-RD-07 AC1): server-side revocation of platform super-admin tokens (PLAT-03 AC1 model: a counter
-- compared with a token claim). Platform access and refresh tokens carry the admin's token_version in the `tv` claim
-- (no claim = version 0); a password change bumps it, so every token minted before the change stops working while the
-- session that changed the password is re-issued at the new version. Additive, re-runnable.
ALTER TABLE platform_admins ADD COLUMN IF NOT EXISTS token_version INTEGER NOT NULL DEFAULT 0;
