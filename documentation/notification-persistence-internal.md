# Notification Persistence Implementation Notes

## Overview

Audience: engineers and maintainers working on admin realtime notifications, backend persistence, and frontend inbox state.

This document explains the backend notification persistence feature that was added to fix a reliability gap in the existing websocket-only notification flow.

Before this change, admin notifications existed only in frontend state after a websocket push. If the browser storage was cleared, the user logged out, or the admin was offline when the event happened, the notification was lost. The backend now persists notification state and tracks read status per recipient.

This is an internal feature note. It is not a frontend usage guide.

## Problem And Intent

The previous implementation in `NotificationService` only sent a websocket message to admins that were currently connected through `AdminSessionManager`.

That created three problems:

- notifications sent while no admin was connected were dropped
- notifications disappeared after frontend session or storage reset
- there was no backend-owned read or unread state

The new implementation changes the source of truth:

- websocket remains the realtime transport
- the database becomes the durable notification store
- read state is tracked per recipient, not globally

## Implementation Summary

The implementation introduces a two-table notification model:

- `Notification`
  - shared event payload
  - stores notification type, title, message, reference fields, and `createdAt`

- `NotificationRecipient`
  - per-user delivery state
  - stores notification/user association, `isRead`, `readAt`, `deliveredAt`, and `createdAt`

This design is required because a single notification can be sent to multiple admins, but each admin needs an independent read state.

### Current Event Flow

For payment success notifications:

1. `SuccessfulOrderHandler.handlePaymentSuccess(...)` calls `NotificationService.notifyAdminsOfSuccessfulPayment(...)`
2. `NotificationService` loads all admin users from `UserRepository`
3. a shared `Notification` row is created
4. one `NotificationRecipient` row is created for each admin
5. only currently connected admins receive a websocket push
6. connected recipients get `deliveredAt` stamped
7. offline admins still keep unread notification rows in the database

This means persistence happens even when no websocket session is active.

## Core Files

Primary implementation files:

- `src/main/java/com/example/perfume_budget/model/Notification.java`
- `src/main/java/com/example/perfume_budget/model/NotificationRecipient.java`
- `src/main/java/com/example/perfume_budget/repository/NotificationRepository.java`
- `src/main/java/com/example/perfume_budget/repository/NotificationRecipientRepository.java`
- `src/main/java/com/example/perfume_budget/config/ws/NotificationService.java`
- `src/main/java/com/example/perfume_budget/service/NotificationInboxServiceImpl.java`
- `src/main/java/com/example/perfume_budget/controller/NotificationController.java`
- `src/main/java/com/example/perfume_budget/mapper/NotificationMapper.java`

Supporting contract files:

- `src/main/java/com/example/perfume_budget/dto/notification/NotificationResponse.java`
- `src/main/java/com/example/perfume_budget/dto/notification/UnreadNotificationCountResponse.java`
- `src/main/java/com/example/perfume_budget/dto/ws/WebSocketNotificationBody.java`

Test coverage added:

- `src/test/java/com/example/perfume_budget/config/ws/NotificationServiceTest.java`
- `src/test/java/com/example/perfume_budget/service/NotificationInboxServiceImplTest.java`

## API Contract

Admin notification inbox endpoints:

- `GET /api/v1/admin/notifications`
  - returns the current admin's notifications as `PageResponse<NotificationResponse>`

- `GET /api/v1/admin/notifications/unread-count`
  - returns `{ unreadCount }`

- `PATCH /api/v1/admin/notifications/{recipientId}/read`
  - marks a single recipient record as read
  - returns the updated `NotificationResponse`

- `PATCH /api/v1/admin/notifications/read-all`
  - marks all unread notifications for the current admin as read
  - returns `204 No Content`

All endpoints are admin-only through `@PreAuthorize("hasRole('ADMIN')")`.

### Websocket Payload Changes

`WebSocketNotificationBody` now carries persisted notification identity and read-state fields instead of the old display-only payment payload.

Current websocket fields:

- `recipientId`
- `notificationId`
- `type`
- `title`
- `message`
- `referenceType`
- `referenceId`
- `read`
- `readAt`
- `deliveredAt`
- `createdAt`

This lets the frontend upsert realtime events into the same state shape returned by the inbox API.

## Key Behaviors And Edge Cases

Important defaults and behaviors:

- persistence happens before websocket fanout
- if no admin is connected, notifications are still stored
- `read` status is owned by `NotificationRecipient`, not `Notification`
- mark-read operations are scoped by both `recipientId` and authenticated user
- unread count is computed from persisted recipient rows
- websocket delivery updates `deliveredAt` only for currently connected admins

### Ownership Rule

The backend does not mark notifications read by `notificationId` alone.

Instead, mark-read uses `NotificationRecipientRepository.findByIdAndUser(...)`. This prevents one admin from mutating another admin's read state for the same shared notification event.

## Flags, Config, Or Migrations

There are no feature flags for this implementation.

Schema rollout currently relies on:

- `spring.jpa.hibernate.ddl-auto=update`

No checked-in Flyway migration was added as part of this change. That is acceptable for current repo conventions, but this data is now user-visible and durable, so an explicit migration strategy would be safer before production hardening.

## Frontend Integration Notes

The backend is now the source of truth for notifications. Frontend behavior should align to that model.

Expected frontend sequence:

1. fetch `GET /api/v1/admin/notifications` on admin app load
2. fetch `GET /api/v1/admin/notifications/unread-count`
3. subscribe to `/user/queue/notifications`
4. upsert incoming websocket messages by `recipientId`
5. call `PATCH /api/v1/admin/notifications/{recipientId}/read` when a notification is viewed
6. call `PATCH /api/v1/admin/notifications/read-all` for bulk read actions if needed

Local storage can still be used as a cache, but it should no longer be treated as authoritative state.

## Testing And Verification

Unit tests currently verify:

- notifications are persisted for all admins even when nobody is connected
- realtime websocket fanout only targets connected admins
- inbox listing maps persisted recipient rows correctly
- unread count is derived from repository state
- mark-read fails when the notification does not belong to the authenticated user
- mark-all-read updates all unread notifications for the authenticated user

Manual verification checklist:

1. Trigger a successful payment while no admin websocket client is connected.
2. Log in as an admin and confirm the notification appears via `GET /api/v1/admin/notifications`.
3. Trigger another successful payment while an admin websocket client is connected.
4. Confirm the notification is received live and also remains present after refresh.
5. Mark a single notification as read and confirm unread count decreases.
6. Run read-all and confirm all unread notifications move to read state.

## Known Gaps

- The frontend still needs to consume the new inbox endpoints and the expanded websocket payload shape.
- Notification creation is currently implemented for payment success events only.
- There is no retention, archival, or delete flow yet for old notifications.
- `deliveredAt` currently reflects websocket delivery attempts for connected admins only. It does not represent frontend rendering or acknowledgment.
- Build verification in this workspace was limited by sandboxed network access. Maven could not resolve dependencies from `repo.maven.apache.org`, so a full compile was not completed in-session.

## Suggested Next Steps

- update the admin frontend to hydrate notifications from backend APIs
- standardize notification `type` values if more event classes will be added
- add explicit database migrations for the new notification tables
- decide on retention and cleanup policy for old read notifications
- add integration tests once the project test environment supports end-to-end API and websocket verification
