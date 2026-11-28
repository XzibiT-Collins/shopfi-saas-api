# Staff notifications API (API-RD-02: notifications)

Base path `/api/v1/admin/notifications`. The inbox of the signed-in staff member: persisted notifications with a read
flag **per person**, plus a realtime push over STOMP. Bodies are `{ "description": string|null, "data": ... }`
([errors.md](errors.md)); branch on the status.

**Who:** `ADMIN` and `FRONT_DESK` (class level `hasAnyRole('ADMIN','FRONT_DESK')`; no per-user front-desk permission is
needed). Anonymous, a foreign-tenant token and a platform token get `401`; `CUSTOMER` gets `403`
([authorization-matrix.md](authorization-matrix.md)).

**Tenancy and privacy:** the inbox lives in the tenant's schema, so another tenant's notifications never exist for the
caller. Within a tenant the inbox is **personal**: you only ever see and change your own rows. A `recipientId` that belongs
to a colleague (or to another tenant, or does not exist) is `404 Notification not found.`, the same answer for all three,
so ids cannot be probed.

| Method | Path | Purpose |
|---|---|---|
| GET | `/?page&size` | my notifications, newest first, paged |
| GET | `/unread-count` | how many of mine are unread (the bell badge) |
| PATCH | `/{recipientId}/read` | mark one of mine as read (idempotent), returns it |
| PATCH | `/read-all` | mark all of mine as read, `204` with no body |

## The notification row

```json notification-response
{
  "description": null,
  "data": {
    "content": [
      { "recipientId": 88, "notificationId": 31, "type": "PAYMENT_SUCCESS", "title": "Payment received",
        "message": "Order #ORD-20260304-0001 has been successfully paid.", "referenceType": "ORDER",
        "referenceId": "ORD-20260304-0001", "read": false, "readAt": null, "deliveredAt": "2026-03-04T10:15:02",
        "createdAt": "2026-03-04T10:15:01" }
    ],
    "totalPages": 1, "totalElements": 1, "size": 20, "number": 1, "numberOfElements": 1,
    "isFirst": true, "isLast": true, "isEmpty": false
  }
}
```

| Field | Meaning |
|---|---|
| `recipientId` | **the id to use in `PATCH /{recipientId}/read`** (one row per staff member per event). Not the same as `notificationId` |
| `notificationId` | the shared event; the same for every staff member who received it |
| `type` | an open string. Today: `PAYMENT_SUCCESS`, `SHOP_FLOOR_RESTOCK`. A client must show an unknown type with its `title` / `message`, not fail |
| `title`, `message` | display text written by the server (English), at most 128 / 512 characters |
| `referenceType`, `referenceId` | what to open: `ORDER` + order number (`PAYMENT_SUCCESS`; the tenant's order, `GET /order/{orderNumber}` is ADMIN only), `PRODUCT` + product id as text (`SHOP_FLOOR_RESTOCK`). Both may be `null` for a future type |
| `read`, `readAt` | this person's read state; `readAt` is `null` while unread |
| `deliveredAt` | when the realtime push reached this person's open session; `null` when they were offline (the row is still in the inbox) |
| `createdAt` | creation of the event. Timestamps are zone-less server-local `yyyy-MM-ddTHH:mm:ss` |

Who gets which event: every `ADMIN` **and** `FRONT_DESK` account of the tenant at the time
of the event; a staff member created afterwards does not get older events.

* `PAYMENT_SUCCESS` (ref `ORDER`): written once per order when its payment is confirmed (a retried or duplicate webhook does
  not write a second one).
* `SHOP_FLOOR_RESTOCK` (ref `PRODUCT`): the shop floor of a product ran low while store rooms still hold stock. A new store has no store room (PLAN-07: one
  location, `Shop Floor`), so this alert only appears once the admin has added a `STORE_ROOM` location holding stock. At most one
  **unread** alert per product exists at a time: while any staff member has it unread, no new alert for that product is made.

## GET /

| Parameter | Meaning | Default / limits |
|---|---|---|
| `page`, `size` | paging; `page` is zero-based in the request and one-based (`number`) in the response | `size` default 20; the dev and prod profiles cap it at **50** (a larger value is lowered, never an error) |

Order: newest first (`createdAt` of the row for this person). `sort` is not part of the contract, do not send it. No filter for read/unread exists: fetch
`/unread-count` for the badge and read the first page for the list.

## GET /unread-count

```json unread-count-response
{ "description": null, "data": { "unreadCount": 3 } }
```

## PATCH /{recipientId}/read

No body. `200` with the updated row (`data` is one notification row as above, `read: true`, `readAt` set). Marking an
already read row changes nothing (`readAt` keeps its first value; the mark-read response may show more fractional-second digits than later reads, compare to the second). Errors: `404 Notification not found.` (not yours, unknown
or foreign tenant), `400` (`Invalid parameter type`) for a non-numeric id.

## PATCH /read-all

No body, `204` with an empty body, also when nothing was unread. Only the caller's own rows change; a colleague's unread
count is untouched.

## Realtime push (STOMP)

Connect to `/ws` from the tenant's origin and subscribe to `/user/queue/notifications` (allowed for `ADMIN` and `FRONT_DESK`
only; see the STOMP section of [authorization-matrix.md](authorization-matrix.md)). Each message is one JSON object with
exactly the fields of a notification row above. The push is addressed per user and tenant, and is sent only after the row is
committed. It is best effort: the inbox (`GET /`) is the source of truth, so refetch it on connect and after a reconnect.

## Frontend follow-up

The admin UI has no notifications bell yet. Build against this contract; use `recipientId` (not `notificationId`) to mark a
row read.

## Subscription billing notifications (BILL-00)

Contract only ([billing.md](billing.md) section 16). Subscription billing is MASTER data, so its notifications do **not** use this inbox: no `Notification` / `NotificationRecipient` rows are written in the tenant schema, `PAYMENT_SUCCESS` here still means a shop order payment, and the STOMP push is not used. Billing e-mails go to the tenant owner e-mail through a master `billing_outbox` and `BillingMailService`; the in-app side is the banner list of `GET /api/v1/admin/billing/summary` (readable by ADMIN, and a non-financial subset by FRONT_DESK), shown by the admin shell next to this bell. The catalogue (trial reminders, payment success and failure, retry and grace reminders, access restricted and restored, renewal reminder, upgrade, downgrade, cancellation, invoice available, refund, price change notice, complimentary, card expiring), the timing setting that controls each one, the template variables and the dedupe keys are in billing.md section 16.2. Clients show an unknown banner `kind` as nothing rather than failing.

