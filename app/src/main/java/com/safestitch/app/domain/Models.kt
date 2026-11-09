package com.safestitch.app.domain

/**
 * What kind of equipment a QR-tagged checkpoint is. Decides which (editable) checklist is used.
 * The five current types come from the SafeStitch brief. Legacy types are kept so equipment created
 * before them keeps its label, QR code and history; an admin can reclassify it at any time.
 * Values are stored by name in the database: never rename a constant.
 */
enum class CheckpointType(val label: String, val legacy: Boolean = false) {
    FIRE_EXTINGUISHER("Fire extinguisher"),
    ELECTRICAL("Electrical panel"),
    FIRE_ALARM("Fire alarm"),
    EMERGENCY_EXIT("Emergency exit"),
    BOILER("Boiler"),
    SEWING_LINE("Sewing line", legacy = true),
    FIRE_SAFETY("Fire safety point", legacy = true),
    GENERAL_AREA("General work area", legacy = true),
    ;

    companion object {
        /** Types offered for new equipment. */
        val current: List<CheckpointType> = entries.filterNot { it.legacy }
    }
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

/**
 * Corrective action lifecycle: OPEN -> IN_PROGRESS -> RESOLVED -> VERIFIED.
 * Anyone signed in may start work and report the fix; only an admin may verify,
 * or send a resolution back to IN_PROGRESS.
 */
enum class ActionStatus(val label: String) {
    OPEN("Open"),
    IN_PROGRESS("In progress"),
    RESOLVED("Resolved – awaiting verification"),
    VERIFIED("Verified"),
    ;

    val isActive: Boolean get() = this == OPEN || this == IN_PROGRESS
}

/** Answer recorded for one checklist item. */
enum class ItemResult(val label: String, val shortLabel: String) {
    UNANSWERED("Not answered", "—"),
    PASS("Pass", "PASS"),
    FAIL("Fail", "FAIL"),
    NOT_APPLICABLE("N/A", "N/A"),
}
