# Theme and Section Contract (THEME-03)

Status: **v1 draft for backend and frontend sign-off.** Single source of truth for `GET/PUT /api/v1/theme`. Backend tickets THEME-01/02/04 and frontend tickets UI-02/UI-07 build against this file and the machine-readable schema.

| Artifact | Path |
|---|---|
| This document | `backend/docs/api/theme-contract.md` |
| JSON Schema (draft 2020-12), canonical | `backend/docs/api/theme.schema.json` |
| Frontend-consumable copy (byte-identical, asserted by the contract test) | `backend/docs/api/frontend/theme.schema.json` |
| Contract test | `backend/src/test/java/com/example/perfume_budget/contract/ThemeContractTest.java` |

The frontend repo should vendor `docs/api/frontend/theme.schema.json` (or generate TypeScript types from it, for example with `json-schema-to-typescript`). Never hand-edit the copy; edit the canonical file and the test fails until the copy is refreshed.

All examples below tagged `<!-- example: ... -->` are extracted and validated by the contract test (valid ones must pass, ones tagged `invalid` must fail). Changing the doc and the schema out of step breaks the build.

## 1. Conventions

- Base path `/api/v1`. The tenant is resolved from the browser `Origin` header (fallback `Referer`) exactly as for every other endpoint (TenantResolutionFilter). Server-side callers (Next.js SSR) **must send `Origin: https://<tenant-host>` explicitly**; Node `fetch` does not.
- Responses use the existing envelope `CustomApiResponse`: `{ "description": string|null, "data": T|null }`. For this contract `data` is the **Theme document** (section 3), and `theme.schema.json` validates `data`, not the envelope.
- Frontend calls use `credentials: "include"` for the admin PUT (JWT cookie). The public GET needs no credentials.
- JSON property names are camelCase. Unknown properties are rejected on write (`additionalProperties: false`) and ignored on read (section 8).

## 2. Design tokens

Tokens are the only styling inputs a tenant controls. Components consume them as CSS variables (`--color-primary`, `--radius`, ...) and must not hard-code tenant colours.

### 2.1 Colours (`tokens.colors`, all required, `#RRGGBB`)

| Key | Role |
|---|---|
| `primary` | Buttons, links, header accents |
| `onPrimary` | Text/icons on `primary` |
| `accent` | Highlights, sale badges, promo banner |
| `onAccent` | Text/icons on `accent` |
| `background` | Page background |
| `surface` | Cards, footer, panels |
| `text` | Body text |
| `mutedText` | Secondary text |

Format: exactly six hex digits with `#`; 3/8-digit hex, `rgb()`, named colours and `var()` are rejected. The server stores lowercase.

**Accessible-contrast constraints** (WCAG 2.1 relative-luminance contrast ratio, enforced server-side, not expressible in JSON Schema). Each pair must be **>= 4.5:1**:

1. `text` on `background`
2. `text` on `surface`
3. `mutedText` on `background`
4. `mutedText` on `surface`
5. `onPrimary` on `primary`
6. `onAccent` on `accent`

Violation: `400`, `code: "CONTRAST_TOO_LOW"`, `path` of the foreground key (for example `tokens.colors.onPrimary`) and message naming the pair and ratio. Formula: `L = 0.2126R + 0.7152G + 0.0722B` with `c <= 0.03928 ? c/12.92 : ((c+0.055)/1.055)^2.4`; ratio `(Lmax+0.05)/(Lmin+0.05)`. The admin UI should pre-check client-side with the same formula; the server is authoritative.

### 2.2 Typography (`tokens.typography`)

- `headingFont`, `bodyFont`: one of the curated allow-list (exact strings): `system`, `Inter`, `Roboto`, `Open Sans`, `Lato`, `Montserrat`, `Poppins`, `Raleway`, `Nunito`, `DM Sans`, `Source Sans 3`, `Playfair Display`, `Merriweather`, `Lora`, `Cormorant Garamond`. `system` means the platform UI font stack and loads nothing. Frontends load only these families (self-hosted via `next/font`), never an arbitrary name from the payload.
- `baseSizePx`: integer 14..20 (root font size; the rest scales in `rem`).

### 2.3 Radius and spacing

- `radius` enum to px: `none` 0, `sm` 4, `md` 8, `lg` 16, `full` 9999 (pill; cards cap at 24px).
- `spacing` enum density: `compact`, `comfortable`, `spacious`. Base scale (px, 4-pt grid): `4, 8, 12, 16, 24, 32, 48, 64`; multiplier `compact` 0.875, `comfortable` 1, `spacious` 1.25 applied to section padding and grid gaps.

### 2.4 Logo and background (`assets`)

Both keys are required and nullable.

- `assets.logo`: `null` or `{ url, alt }`. `alt` is 1..80 plain-text chars. `url` is a Cloudinary URL (section 7.2).
- `assets.background`: `null` or `{ url, overlayOpacity? }`. Page background image; `overlayOpacity` 0..0.8 (default 0) draws a `background`-coloured veil for legibility. `tokens.colors.background` is always the fallback colour.

## 3. Theme document

```
Theme {
  schemaVersion: 1                  // integer, const per schema version
  revision:      integer >= 0       // server-managed, readOnly, ignored on PUT
  updatedAt:     date-time          // server-managed, readOnly, ignored on PUT
  tokens:        { colors, typography, radius, spacing }
  assets:        { logo, background }
  pages:         { home: Section[], product: Section[], footer: Section[] }
}
Section { id: string, type: string, props: object, visible: boolean, order: integer }
```

### 3.1 Section registry rules

- `id`: `^[a-z0-9][a-z0-9-]{0,39}$`, unique across the whole document (all slots). Stable identity for React keys and for upload part names (`image.<id>`).
- `type`: one of the v1 types in section 4. Unknown type on write: `400 UNKNOWN_SECTION_TYPE`.
- `order`: integer 0..99. Renderers sort ascending by `order`; array position carries no meaning. Duplicate `order` inside one slot: `400 DUPLICATE_ORDER`.
- `visible`: `false` keeps the section in the document (editable) but it is not rendered.
- `props`: object validated by the type's definition; unknown props rejected on write.

### 3.2 Page slots

| Slot | Rendered on | Allowed types | Max sections |
|---|---|---|---|
| `home` | Storefront home `/` | `hero`, `featured-products`, `category-grid`, `banner`, `rich-text` | 12 |
| `product` | Product detail page, below the product block | `featured-products`, `banner`, `rich-text` | 6 |
| `footer` | Every page footer | `footer` | 1 |

A type in a slot it is not allowed in: `400 SECTION_NOT_ALLOWED_IN_SLOT`. Document-wide cap 19 sections (12+6+1), duplicate ids `400 DUPLICATE_SECTION_ID`.

## 4. v1 section types

All text props are **plain text** (`^[^<>\u0000-\u001F\u007F]*$`): no angle brackets, no control chars, rendered as text. Lengths are in UTF-16 code units as counted by JSON Schema `maxLength`. "link" = same-site path (`/...`, not `//`) or `https://` URL (section 7.3). "image" = Cloudinary URL (section 7.2).

### 4.1 `hero`

| Prop | Type | Required | Limits / default |
|---|---|---|---|
| `heading` | text | yes | 1..80 |
| `subheading` | text | no | 0..200 |
| `imageUrl` | image or null | no | upload part `image.<sectionId>` sets it |
| `imageAlt` | text | no | 0..120 (empty = decorative) |
| `alignment` | `left`/`center`/`right` | no | `center` |
| `overlayOpacity` | number | no | 0..0.8, `0.4` |
| `ctaLabel`, `ctaHref` | text 1..30, link | no | both or neither |

### 4.2 `featured-products`

Renders product cards from existing `ProductListing` data (`productId`, `productName`, `price`, `originalPrice`, `onSale`, `productImageUrl`, `slug`, ...). The frontend resolves data itself from the product API; the section only stores selection rules.

| Prop | Type | Required | Limits / default |
|---|---|---|---|
| `title` | text | no | 0..80 |
| `source` | `featured` / `category` / `manual` | yes | |
| `categoryId` | integer >= 1 | iff `source=category` | `CategoryResponse.categoryId` |
| `productIds` | integer[] | iff `source=manual` | 1..12, unique, `ProductListing.productId` |
| `limit` | integer | no | 1..12, `8` (`featured` = `GET /product/featured`, `category` = `GET /product/search?categoryId=`) |
| `columns` | integer | no | 2..4, `4` |

Forbidden props for the wrong source (for example `productIds` with `source=featured`) fail validation. On the **product** slot the section is a "related products" strip and the frontend excludes the current product. Reference existence: on PUT the server checks every `categoryId`/`productId` exists in the tenant (`400 UNKNOWN_REFERENCE`). At render time ids that no longer exist or are inactive/not enlisted are silently skipped; an empty result hides the section.

### 4.3 `category-grid`

| Prop | Type | Required | Limits / default |
|---|---|---|---|
| `title` | text | no | 0..80 |
| `categoryIds` | integer[] | no | 0..12 unique; empty = all categories (first 12, API order) |
| `columns` | integer | no | 2..4, `3` |
| `showDescription` | boolean | no | `false` |

Uses `CategoryResponse` (`categoryId`, `categoryName`, `description`, `slug`). There is no category image in v1. Same `UNKNOWN_REFERENCE` rule as above.

### 4.4 `banner`

| Prop | Type | Required | Limits / default |
|---|---|---|---|
| `message` | text | yes | 1..160 |
| `tone` | `primary`/`accent`/`neutral` | no | `primary`; maps to token pairs (`primary`/`onPrimary`, `accent`/`onAccent`, `surface`/`text`) so contrast holds by construction |
| `linkLabel`, `linkHref` | text 1..30, link | no | both or neither |
| `dismissible` | boolean | no | `false` (dismissal is client-local only) |

### 4.5 `rich-text`

| Prop | Type | Required | Limits / default |
|---|---|---|---|
| `title` | text | no | 0..80 |
| `body` | restricted Markdown | yes | 1..5000 chars |
| `align` | `left`/`center` | no | `left` |

Allowed Markdown subset: paragraphs, `**bold**`, `*italic*`, headings `##` and `###` only, `-`/`1.` lists (one level), links `[text](dest)`, hard line breaks. Everything else (raw HTML, images `![]()`, tables, code blocks, `#` h1, blockquotes, HTML entities that decode to `<`) is stripped or rejected per section 7.1.

### 4.6 `footer`

| Prop | Type | Required | Limits / default |
|---|---|---|---|
| `tagline` | text | no | 0..160 |
| `linkGroups` | `{title: text 1..30, links: {label: text 1..30, href: link}[1..6]}[]` | no | 0..3 groups |
| `social` | `{network, url}[]` | no | 0..6; `network` in `facebook, instagram, x, tiktok, youtube, linkedin, whatsapp, pinterest`; `url` must be https on that network's host allow-list (section 7.3) |
| `copyrightText` | text | no | 0..120; empty means render `(c) <year> <store name>` |

## 5. Endpoints

### 5.1 `GET /api/v1/theme`

Public (no auth), tenant from Origin/Referer, cacheable.

Request headers: `Origin` (or `Referer`) required; optional `If-None-Match`.

| Status | When | Body |
|---|---|---|
| 200 | Theme returned (stored, or generated defaults for a fresh tenant, section 9) | `{ "description": null, "data": Theme }` |
| 304 | `If-None-Match` matches current ETag | empty |
| 400 | No tenant signal (no Origin/Referer) | `{ "description": "...", "data": null }` |
| 404 | Unknown tenant | same |
| 423 | Tenant suspended | same |

Response headers (200 and 304): `Cache-Control: public, max-age=60, s-maxage=300, stale-while-revalidate=600`, `Vary: Origin`, `ETag: "<revision>-<first 16 hex of SHA-256 of the canonical JSON of data>"`. Error responses carry `Cache-Control: no-store`.

### 5.2 `PUT /api/v1/theme`

ADMIN of the resolved tenant (`@PreAuthorize("hasRole('ADMIN')")`). **Full replacement** of tokens, assets and pages (not a patch): the client sends the whole document it wants stored. Two accepted forms:

1. **JSON only** (tokens/sections, no new files): `Content-Type: application/json`, body = Theme (without server-managed fields; if sent they are ignored).
2. **Multipart** (any file upload): `Content-Type: multipart/form-data` with parts:

| Part | Content-Type | Required | Notes |
|---|---|---|---|
| `theme` | `application/json` | yes | Theme document |
| `logo` | `image/png`, `image/jpeg`, `image/webp` | no | replaces `assets.logo.url`; `assets.logo.alt` must be in `theme` |
| `background` | same | no | replaces `assets.background.url` |
| `image.<sectionId>` | same | no | replaces `props.imageUrl` of that `hero` section (`<sectionId>` must exist in `theme`) |

Optional request header `If-Match: "<etag>"` (value from the last GET): a stale value returns `412` so concurrent admins do not silently overwrite each other. Omitted means last write wins.

Asset URL rules in the submitted JSON when **no** file part replaces them: `null` removes the asset; a URL is accepted only if it equals the currently stored value or lives under the tenant's own branding folder (section 7.2); anything else is `400 ASSET_URL_NOT_OWNED`. Removed or replaced Cloudinary assets are deleted best-effort.

| Status | When |
|---|---|
| 200 | Stored. Body `{ "description": "Theme updated", "data": Theme }` with new `revision`, `updatedAt`; header `ETag` of the new document; `Cache-Control: no-store` |
| 400 | Validation failed (shape below), malformed JSON/multipart, unsupported content type of a part |
| 401 | Not authenticated |
| 403 | Authenticated but not ADMIN of this tenant (CUSTOMER, or an admin token for another tenant) |
| 404 / 423 | Unknown / suspended tenant (resolution filter) |
| 412 | `If-Match` does not match current ETag |
| 413 | Request body > 64 KB (JSON form), a file over its limit, or multipart request over 12 MB |
| 415 | Content type is neither JSON nor multipart |
| 502 | Cloudinary upload failed (nothing is persisted; theme unchanged) |

Success and failure are atomic: files are uploaded first; if validation or persistence then fails the uploads are cleaned up and the stored theme is unchanged.

### 5.3 Error shape (400)

Uses the existing envelope with `data` as a list of field errors (this is a deliberate improvement on the current `List<String>` validation shape; THEME-02/04 introduce the structured form for this endpoint only):

```json
{
  "description": "Theme validation failed",
  "data": [
    { "path": "pages.home[1].props.heading", "code": "TOO_LONG", "message": "must be at most 80 characters" },
    { "path": "tokens.colors.onPrimary", "code": "CONTRAST_TOO_LOW", "message": "onPrimary on primary has contrast 2.1:1, need 4.5:1" }
  ]
}
```

`path` is a JSON path into the submitted theme (dots and `[index]`). `code` is a stable machine string; `message` is English, for display only. Codes: `REQUIRED`, `WRONG_TYPE`, `INVALID_FORMAT`, `OUT_OF_RANGE`, `TOO_LONG`, `TOO_SHORT`, `NOT_ALLOWED_VALUE` (enum/font), `UNKNOWN_PROPERTY`, `UNKNOWN_SECTION_TYPE`, `SECTION_NOT_ALLOWED_IN_SLOT`, `TOO_MANY_SECTIONS`, `DUPLICATE_SECTION_ID`, `DUPLICATE_ORDER`, `CONTRAST_TOO_LOW`, `UNSAFE_CONTENT` (HTML/script/forbidden Markdown), `RICH_TEXT_TOO_DEEP`, `RICH_TEXT_TOO_MANY_LINES`, `RICH_TEXT_BUDGET_EXCEEDED`, `URL_NOT_ALLOWED`, `ASSET_URL_NOT_OWNED`, `UNKNOWN_REFERENCE`, `UNSUPPORTED_SCHEMA_VERSION`, `FILE_TYPE_NOT_ALLOWED`. The first 400 returns all errors found (cap 50), not just the first.

413, 401, 403, 412, 415 and 502 use the existing envelope with a plain-string `description` and `data: null`.

## 6. Versioning and forward compatibility

- `schemaVersion` is a positive integer; this document and `theme.schema.json` define **1**. The schema pins `"const": 1`; a future v2 ships as a new `$id` (`urn:shopfi:theme:v2`) and file, v1 is kept.
- **Non-breaking (no bump):** new optional props on a section, new optional tokens, new section types, new enum values for non-token props, new allowed slots. Schema is updated in place; this document's changelog records it.
- **Breaking (bump):** removing/renaming a prop or type, new required prop, changed meaning or tightened limits of a stored value, new required token.
- **Server (write path):** `PUT` accepts the current version and the previous one (N-1) migrated up through registered pure functions `migrate(vN -> vN+1)`; anything else is `400 UNSUPPORTED_SCHEMA_VERSION` (path `schemaVersion`). The stored document always carries its version; **GET migrates stored older documents in memory to the current version** (and lazily persists on next write), so the frontend never sees an old version from a current server.
- **Server (read path, e.g. a rollback):** the server never emits a version it does not know.
- **Client rules:** (1) a renderer must ignore unknown section `type`s and unknown `props`, log a warning and skip rendering that section; never throw. (2) If `schemaVersion` is greater than the client knows, render tokens and any sections whose type it knows, and log; if `tokens` cannot be read, use the built-in default theme (section 9). (3) Missing optional props take the defaults in section 4. (4) The client must not send a `schemaVersion` it does not know; the admin editor loads the document, edits it and sends it back with the same version, otherwise the editor is read-only and prompts to upgrade.

## 7. Safety

### 7.1 No raw HTML, CSS or JS from tenants

- Tenants submit only data (tokens, enums, plain text, Markdown subset, ids, Cloudinary/https URLs). There is **no** field that carries HTML, CSS, SVG markup, a script, a style attribute, a CSS class name or a font URL. Colours are validated hex, fonts are enum-allow-listed, so nothing is interpolated into CSS unvalidated.
- Plain-text props reject `<` and `>` and control characters. Rendered as text nodes only.
- **Rich-text sanitization (server and client both):**
  1. Reject (do not silently strip) any `<` or `>` in `body` (`UNSAFE_CONTENT`). No raw HTML, no autolinks `<...>`.
  2. Reject Markdown images `![..](..)`.
  3. Parse with a CommonMark parser with HTML disabled (for example commonmark-java without the HTML extension; reject on any `HtmlInline`/`HtmlBlock`/`Image`/code-block/table/blockquote node). Link destinations are checked **after** parsing and entity decoding, against the link allow-list (7.3). `javascript:`, `data:`, `vbscript:`, `file:`, `blob:` and any scheme other than `https` are rejected; `mailto:` and `tel:` are allowed in v1.
  4. Control characters other than `\n`, `\r`, `\t` are rejected; max **5000 characters per body, counted in Unicode code points** (an emoji is 1, not 2; the same count the schema's `maxLength` uses). The server runs the safety checker on EVERY body regardless of length: a body over the cap is rejected with `TOO_LONG`, never skipped or partially checked.
  4a. **Nesting cap (renderer DoS guard):** on any line, indentation (2 columns = 1 level, tab = 4) plus consecutive list/quote markers (`- `, `* `, `+ `, `1. `, `1) `, `> `) may total at most **3** levels. Deeper input (for example `- - - - x`, or `'- '.repeat(1200)`) is rejected with `RICH_TEXT_TOO_DEEP` at the section's `props.body` path. The server checks this with a linear scan before parsing; renderers may rely on it.
  4b. **Block-line cap:** at most **60** lines per body may start with a list or quote marker (including an empty `-`). More is rejected with `RICH_TEXT_TOO_MANY_LINES` at `props.body`. Line endings are LF, CR and CRLF; the nesting scan (4a) and this cap treat all three as line ends.
  4c. **Document budget:** the total length of all `rich-text` bodies in one theme document (home + product slots), in code points, is at most **8000**. The section whose body pushes the running total over the budget (slots are visited footer, home, product, in array order) gets `RICH_TEXT_BUDGET_EXCEEDED` at its `props.body` path. Renderers may rely on 4a-4c to bound total Markdown render cost.
  5. Stored value is the original Markdown, not rendered HTML. The schema's `body` patterns are a first line of defence only (angle brackets, images, `javascript:`/`data:`/`vbscript:` link destinations); step 3 is authoritative.
  6. The frontend renders with a Markdown renderer with HTML disabled (for example `react-markdown` without `rehype-raw`), renders links with `rel="noopener noreferrer nofollow"` and `target="_blank"` for external hosts, and **never uses `dangerouslySetInnerHTML`** for any theme field.
- Server-side XSS tests (THEME-02 AC4) must cover: `<script>`, `<img onerror>`, `[x](javascript:alert(1))`, mixed-case/whitespace/entity-encoded schemes (`JaVa\nScRiPt:`, `&#106;avascript:`), `data:text/html`, in every text field and in `rich-text.body`.

### 7.2 Uploads and Cloudinary

- Allowed types: **PNG, JPEG, WebP** (declared Content-Type AND magic-byte sniffing must agree; filename extension is ignored). **SVG is rejected in v1** (script vector); GIF/AVIF/PDF/others are rejected (`400 FILE_TYPE_NOT_ALLOWED`).
- Size limits: `logo` <= 2 MB; `background` <= 5 MB; `image.<id>` <= 5 MB; whole multipart request <= 12 MB. Exceeding: `413`. Max pixel dimensions 4096x4096 (`400`). The current global `spring.servlet.multipart.max-file-size=20MB` is looser than this contract; THEME-02 enforces the stricter limits in the service and sets request-size handling for this endpoint.
- Storage: Cloudinary folder `shopfi/<tenantSlug>/branding/`, `resource_type=image`, public ids `logo-<16 hex>`, `background-<16 hex>`, `section-<sectionId>-<16 hex>`: **every upload gets a brand-new random id and nothing is ever overwritten**, so a stale (`412`), conflicting or failed update cannot alter an asset the live theme references; assets this request created are deleted if it fails, and assets no longer referenced are deleted after a successful commit. The `If-Match` version is compared with the stored version before any upload is attempted. The folder is derived from the resolved tenant, never from client input. `CloudinaryFileUploadUtil` currently hard-codes folder `images`; THEME-02 adds a branding-folder method.
- Stored/returned URL = Cloudinary `secure_url`. Pattern (stored and accepted URLs): `https://res.cloudinary.com/<cloud>/image/upload/[v<digits>/]shopfi/<tenant>/branding/<file>` where `<file>` matches `(logo|background)(-<16 hex>)?\.(png|jpe?g|webp)` or `section-[a-z0-9-]+\.(png|jpe?g|webp)`. **No other path segment is accepted**: transformation segments (`l_fetch`, `l_text`, `fl_attachment`, `w_`, `h_`, `e_`, `f_auto` ...), `.svg`, sub-folders, `%`, `?`, `#` and `..` are rejected (`ASSET_URL_NOT_OWNED` / schema `INVALID_FORMAT`). The server also requires `<cloud>` to equal the configured cloud name and `<tenant>` to be the caller's slug (for any URL not equal to the currently stored one). Frontends must only render URLs matching this pattern; they may add their own delivery transformations when RENDERING (`f_auto,q_auto,w_...` after `/upload/`) but must never accept one from the document, and must allow-list `res.cloudinary.com` in `next/image`.

### 7.3 URL allow-lists

| Context | Allowed |
|---|---|
| Images (logo, background, hero) | `https://res.cloudinary.com/<cloud>/image/upload/[v<digits>/]shopfi/<tenant>/branding/<file>` only (see 7.2; no transformation segments, no SVG) |
| `link` props (cta, banner, footer links) | same-site path starting with a single `/`, or `https://` URL (any host). No `http:`, `//host`, `javascript:`, `data:`, relative `../`. Max 512 chars, no whitespace, `<`, `>`, quotes or backslash |
| Rich-text link destinations | same as `link` plus `mailto:` and `tel:` |
| Footer `social[].url` | `https://` and host in `facebook.com, instagram.com, x.com, twitter.com, tiktok.com, youtube.com, linkedin.com, wa.me, whatsapp.com, pinterest.com` (optional `www.`) |

External links render with `rel="noopener noreferrer nofollow"`.

### 7.4 Size and abuse limits

- JSON theme document <= **64 KB** serialized (JSON form and the `theme` part). Larger: `413`.
- Max sections: 12 home, 6 product, 1 footer. Max nesting is fixed by the schema (no recursive structures).
- `PUT` is ADMIN-only; tenant comes from the resolved context, never from the body. A tenant can never read or write another tenant's theme or assets. Rate limiting of `PUT` (suggested 30/min/admin) is a THEME-04 decision, not part of the contract.

## 8. Reading rules for the frontend

1. Fetch `GET /theme` server-side in the root layout with the explicit `Origin` header; inject tokens as CSS variables before first paint (no flash).
2. Validate nothing at runtime beyond a cheap `schemaVersion` check; the server already validated. Treat unknown values defensively (section 6 client rules).
3. Sections per slot: filter `visible`, sort by `order`, map `type` to a component in a registry; unknown type renders nothing.
4. Use the ETag with `fetch` revalidation; Next `revalidate: 60` matches `max-age`.

## 9. Defaults for a brand-new tenant

Provisioning (THEME-01) seeds the default row; `GET` for a tenant with no stored theme returns the same document (`revision: 0`). Default tokens: `primary #111827`, `onPrimary #ffffff`, `accent #b45309`, `onAccent #ffffff`, `background #ffffff`, `surface #f9fafb`, `text #111827`, `mutedText #4b5563`, fonts `Inter`/`Inter`, `baseSizePx 16`, radius `md`, spacing `comfortable`, no logo, no background. Home: `hero` (heading is the store display name when known, else "Welcome to our store", truncated to 80), `featured-products` (source `featured`), `category-grid` (all categories). Product slot empty. Footer: one empty `footer` section. All default colour pairs meet the contrast rules (asserted by the contract test).

<!-- example: default-new-tenant valid -->
```json
{
  "schemaVersion": 1,
  "revision": 0,
  "updatedAt": "2026-11-13T10:00:00Z",
  "tokens": {
    "colors": {
      "primary": "#111827",
      "onPrimary": "#ffffff",
      "accent": "#b45309",
      "onAccent": "#ffffff",
      "background": "#ffffff",
      "surface": "#f9fafb",
      "text": "#111827",
      "mutedText": "#4b5563"
    },
    "typography": {
      "headingFont": "Inter",
      "bodyFont": "Inter",
      "baseSizePx": 16
    },
    "radius": "md",
    "spacing": "comfortable"
  },
  "assets": {
    "logo": null,
    "background": null
  },
  "pages": {
    "home": [
      {
        "id": "hero",
        "type": "hero",
        "props": {
          "heading": "Welcome to our store",
          "alignment": "center",
          "overlayOpacity": 0.4
        },
        "visible": true,
        "order": 0
      },
      {
        "id": "featured",
        "type": "featured-products",
        "props": {
          "title": "Featured",
          "source": "featured",
          "limit": 8,
          "columns": 4
        },
        "visible": true,
        "order": 1
      },
      {
        "id": "categories",
        "type": "category-grid",
        "props": {
          "title": "Shop by category",
          "categoryIds": [],
          "columns": 3,
          "showDescription": false
        },
        "visible": true,
        "order": 2
      }
    ],
    "product": [],
    "footer": [
      {
        "id": "footer",
        "type": "footer",
        "props": {
          "tagline": "",
          "linkGroups": [],
          "social": [],
          "copyrightText": ""
        },
        "visible": true,
        "order": 0
      }
    ]
  }
}
```

## 10. Example payloads

Two full, deliberately different themes used by UI-02 tests (`acme`: serif, warm, spacious, light; `bravo`: dark, compact, pill radius, minimal). `acme` also contains a hidden section and uses all six types.

<!-- example: acme-full valid -->
```json
{
  "schemaVersion": 1,
  "revision": 7,
  "updatedAt": "2026-11-13T12:30:00Z",
  "tokens": {
    "colors": {
      "primary": "#0b3d2e",
      "onPrimary": "#ffffff",
      "accent": "#c2410c",
      "onAccent": "#ffffff",
      "background": "#fffdf8",
      "surface": "#f3efe6",
      "text": "#1c1917",
      "mutedText": "#57534e"
    },
    "typography": {
      "headingFont": "Playfair Display",
      "bodyFont": "Lato",
      "baseSizePx": 17
    },
    "radius": "sm",
    "spacing": "spacious"
  },
  "assets": {
    "logo": {
      "url": "https://res.cloudinary.com/shopfi-demo/image/upload/v1731500000/shopfi/acme/branding/logo.png",
      "alt": "Acme Perfumes"
    },
    "background": {
      "url": "https://res.cloudinary.com/shopfi-demo/image/upload/v1731500001/shopfi/acme/branding/background.jpg",
      "overlayOpacity": 0.2
    }
  },
  "pages": {
    "home": [
      {
        "id": "promo",
        "type": "banner",
        "props": {
          "message": "Free delivery over GHS 500",
          "tone": "accent",
          "linkLabel": "Shop now",
          "linkHref": "/products",
          "dismissible": true
        },
        "visible": true,
        "order": 0
      },
      {
        "id": "hero",
        "type": "hero",
        "props": {
          "heading": "Scents that tell your story",
          "subheading": "Hand-picked fragrances, delivered across Ghana.",
          "imageUrl": "https://res.cloudinary.com/shopfi-demo/image/upload/v1731500002/shopfi/acme/branding/section-hero.jpg",
          "imageAlt": "Perfume bottles on a marble shelf",
          "alignment": "left",
          "overlayOpacity": 0.5,
          "ctaLabel": "Browse the collection",
          "ctaHref": "/products"
        },
        "visible": true,
        "order": 1
      },
      {
        "id": "best-sellers",
        "type": "featured-products",
        "props": {
          "title": "Best sellers",
          "source": "featured",
          "limit": 8,
          "columns": 4
        },
        "visible": true,
        "order": 2
      },
      {
        "id": "pick",
        "type": "featured-products",
        "props": {
          "title": "Staff picks",
          "source": "manual",
          "productIds": [
            12,
            15,
            31
          ],
          "columns": 3
        },
        "visible": true,
        "order": 3
      },
      {
        "id": "cats",
        "type": "category-grid",
        "props": {
          "title": "Shop by category",
          "categoryIds": [
            1,
            2,
            4
          ],
          "columns": 3,
          "showDescription": true
        },
        "visible": true,
        "order": 4
      },
      {
        "id": "story",
        "type": "rich-text",
        "props": {
          "title": "Our story",
          "body": "## Made with care\n\nAcme started in **Accra** in 2019.\n\n- Authentic brands only\n- Same-day dispatch\n\nRead our [delivery policy](/delivery) or visit [our guide](https://example.com/guide).",
          "align": "left"
        },
        "visible": true,
        "order": 5
      },
      {
        "id": "old-sale",
        "type": "banner",
        "props": {
          "message": "Black Friday is over",
          "tone": "neutral"
        },
        "visible": false,
        "order": 6
      }
    ],
    "product": [
      {
        "id": "shipping-note",
        "type": "rich-text",
        "props": {
          "body": "Orders placed before **2pm** ship the same day."
        },
        "visible": true,
        "order": 0
      },
      {
        "id": "related",
        "type": "featured-products",
        "props": {
          "title": "You may also like",
          "source": "category",
          "categoryId": 2,
          "limit": 4,
          "columns": 4
        },
        "visible": true,
        "order": 1
      }
    ],
    "footer": [
      {
        "id": "footer",
        "type": "footer",
        "props": {
          "tagline": "Fragrance, simply.",
          "linkGroups": [
            {
              "title": "Help",
              "links": [
                {
                  "label": "Delivery",
                  "href": "/delivery"
                },
                {
                  "label": "Contact",
                  "href": "/contact"
                }
              ]
            }
          ],
          "social": [
            {
              "network": "instagram",
              "url": "https://www.instagram.com/acmeperfumes"
            },
            {
              "network": "whatsapp",
              "url": "https://wa.me/233200000000"
            }
          ],
          "copyrightText": "(c) 2026 Acme Perfumes Ltd"
        },
        "visible": true,
        "order": 0
      }
    ]
  }
}
```

<!-- example: bravo-minimal valid -->
```json
{
  "schemaVersion": 1,
  "revision": 2,
  "updatedAt": "2026-11-13T12:45:00Z",
  "tokens": {
    "colors": {
      "primary": "#1d4ed8",
      "onPrimary": "#ffffff",
      "accent": "#fde047",
      "onAccent": "#1c1917",
      "background": "#0f172a",
      "surface": "#1e293b",
      "text": "#f8fafc",
      "mutedText": "#cbd5e1"
    },
    "typography": {
      "headingFont": "Montserrat",
      "bodyFont": "Open Sans",
      "baseSizePx": 16
    },
    "radius": "full",
    "spacing": "compact"
  },
  "assets": {
    "logo": null,
    "background": null
  },
  "pages": {
    "home": [
      {
        "id": "hero",
        "type": "hero",
        "props": {
          "heading": "Bravo Gadgets",
          "subheading": "Phones, audio and accessories.",
          "alignment": "center",
          "ctaLabel": "See deals",
          "ctaHref": "/products"
        },
        "visible": true,
        "order": 0
      },
      {
        "id": "new",
        "type": "featured-products",
        "props": {
          "source": "featured",
          "limit": 4,
          "columns": 4
        },
        "visible": true,
        "order": 1
      }
    ],
    "product": [],
    "footer": [
      {
        "id": "footer",
        "type": "footer",
        "props": {
          "social": [
            {
              "network": "x",
              "url": "https://x.com/bravogadgets"
            }
          ]
        },
        "visible": true,
        "order": 0
      }
    ]
  }
}
```

### 10.1 Multipart request shape (logo upload)

```
PUT /api/v1/theme
Origin: https://acme.shopfi.app
Content-Type: multipart/form-data; boundary=X
If-Match: "7-3f9a1c2b4d5e6f70"

--X  name="theme"  Content-Type: application/json   (Theme JSON; assets.logo = {"url": null-or-old, "alt": "Acme Perfumes"})
--X  name="logo"   Content-Type: image/png          (binary)
--X  name="image.hero" Content-Type: image/jpeg     (binary)
```

When a `logo` part is present, `assets.logo.url` in the JSON is ignored and may be any valid Cloudinary URL.

### 10.2 Example error responses

`400` (two errors):

```json
{
  "description": "Theme validation failed",
  "data": [
    { "path": "pages.home[0].type", "code": "UNKNOWN_SECTION_TYPE", "message": "unknown section type 'carousel'" },
    { "path": "pages.home[2].props.body", "code": "UNSAFE_CONTENT", "message": "raw HTML is not allowed" }
  ]
}
```

`413`: `{ "description": "Logo must be 2 MB or smaller", "data": null }`

### 10.3 Invalid examples (must fail the schema)

<!-- example: invalid-unknown-section-type invalid -->
```json
{ "schemaVersion": 1, "tokens": { "colors": { "primary": "#111827", "onPrimary": "#ffffff", "accent": "#b45309", "onAccent": "#ffffff", "background": "#ffffff", "surface": "#f9fafb", "text": "#111827", "mutedText": "#4b5563" }, "typography": { "headingFont": "Inter", "bodyFont": "Inter", "baseSizePx": 16 }, "radius": "md", "spacing": "comfortable" }, "assets": { "logo": null, "background": null }, "pages": { "home": [ { "id": "c1", "type": "carousel", "props": {}, "visible": true, "order": 0 } ], "product": [], "footer": [] } }
```

## 11. Changelog

- v1 (THEME-03): initial contract.
