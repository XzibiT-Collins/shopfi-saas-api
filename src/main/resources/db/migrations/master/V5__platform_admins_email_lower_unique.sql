-- Platform admin emails are matched case-insensitively (login lowercases input). platform_admins is only ever
-- written by the PLAT-01 bootstrap, which stores the lowercased email, so no case-variant duplicates can exist.
CREATE UNIQUE INDEX uq_platform_admins_email_lower ON platform_admins (lower(email));
