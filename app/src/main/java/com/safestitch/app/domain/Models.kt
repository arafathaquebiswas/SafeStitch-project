package com.safestitch.app.domain

/** What kind of place or equipment a QR-tagged checkpoint is. Decides which checklist is used. */
enum class CheckpointType(val label: String) {
    SEWING_LINE("Sewing line"),
    FIRE_SAFETY("Fire safety point"),
    ELECTRICAL("Electrical panel"),
    GENERAL_AREA("General work area"),
}

enum class UserRole(val label: String) {
    ADMIN("Admin"),
    INSPECTOR("Inspector"),
}

/** Priority of a finding / corrective action, with the default number of days to fix it. */
enum class Priority(val label: String, val defaultDueDays: Int) {
    CRITICAL("Critical", 1),
    HIGH("High", 3),
    MEDIUM("Medium", 7),
    LOW("Low", 14),
}

/** Corrective action lifecycle: OPEN -> RESOLVED (by anyone) -> VERIFIED (by an admin); admins can reopen. */
enum class ActionStatus(val label: String) {
    OPEN("Open"),
    RESOLVED("Awaiting verification"),
    VERIFIED("Verified"),
}

/** Answer recorded for one checklist item. */
enum class ItemResult(val label: String, val shortLabel: String) {
    UNANSWERED("Not answered", "—"),
    PASS("Pass", "PASS"),
    FAIL("Fail", "FAIL"),
    NOT_APPLICABLE("N/A", "N/A"),
}
