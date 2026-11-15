# Gaps and unverified flows

## Not verified on a device

- Camera permission, QR decoding, gallery image selection, and captured-photo persistence.
- Microphone permission and audio transcription in Safety Chat.
- Android document-provider Save a copy and external PDF sharing.
- Optional HTTPS sync, WorkManager retry behavior, and server conflict resolution.
- Complete admin, authentication, chat, sync, and captured-photo journeys through the device smoke test.

## Access-control caveat

The dashboard hides Admin tools from inspectors and repository methods protect several account operations. However, `SafeStitchNavHost` registers `AdminRoute` without a route-level `user.isAdmin` guard. This was not changed because the requested deliverables are documentation-only; direct/deep-link enforcement remains a verification/security follow-up.

## Dialogs and transient UI represented by reference

Password change; user add/reset; location add/rename; checklist add/edit; sync resend/conflict resolution; inspection delete/problem summary; corrective-action send-back; captured-detail delete; dropdown/date/priority pickers. These are implemented transient states attached to their parent screen, not independent navigation routes.

## Status classification

No route was labeled partial or unreachable based on the source: every registered route has a corresponding composable. The limitations above are environment verification gaps, not claims that those integrations were exercised successfully.
