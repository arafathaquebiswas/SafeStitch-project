package com.safestitch.app.domain

/** What kind of place or equipment a QR-tagged checkpoint is. Decides which checklist is used. */
enum class CheckpointType(val label: String) {
    SEWING_LINE("Sewing line"),
    FIRE_SAFETY("Fire safety point"),
    ELECTRICAL("Electrical panel"),
    GENERAL_AREA("General work area"),
}

/** Answer recorded for one checklist item. */
enum class ItemResult(val label: String, val shortLabel: String) {
    UNANSWERED("Not answered", "—"),
    PASS("Pass", "PASS"),
    FAIL("Fail", "FAIL"),
    NOT_APPLICABLE("N/A", "N/A"),
}
