package com.safestitch.app.domain

data class ChecklistTemplateItem(
    val section: String,
    val question: String,
    val critical: Boolean = false,
)

/**
 * Built-in starting checklists. They are copied into the editable template_questions table when the
 * database is created; admins then edit them in the app. Items are copied into each new inspection,
 * so later edits never change inspections that were already recorded.
 */
object ChecklistTemplates {
    const val EXTINGUISHER = "Fire extinguisher"
    const val ELECTRICAL = "Electrical safety"
    const val ALARM = "Fire alarm"
    const val EXIT = "Emergency exit"
    const val BOILER = "Boiler safety"

    // Legacy sections (equipment created before the five current types)
    const val WORKPLACE = "Workplace safety"
    const val MACHINE = "Machine safety"
    const val STITCH = "Stitch quality"
    const val FIRE = "Fire protection"
    const val HOUSEKEEPING = "Housekeeping & storage"

    private val fireExtinguisher = listOf(
        ChecklistTemplateItem(EXTINGUISHER, "Extinguisher is in its marked place and mounted at the right height"),
        ChecklistTemplateItem(EXTINGUISHER, "Pressure gauge reads in the green (charged) zone", critical = true),
        ChecklistTemplateItem(EXTINGUISHER, "Safety pin and tamper seal are intact", critical = true),
        ChecklistTemplateItem(EXTINGUISHER, "Service tag shows a check within the last 12 months", critical = true),
        ChecklistTemplateItem(EXTINGUISHER, "Hose and nozzle are undamaged and unblocked"),
        ChecklistTemplateItem(EXTINGUISHER, "Correct type for the risk (e.g. CO2 near electrical panels)"),
        ChecklistTemplateItem(EXTINGUISHER, "Access is clear and the location sign is visible"),
    )

    private val electrical = listOf(
        ChecklistTemplateItem(ELECTRICAL, "Panel door is closed, labelled and free of obstruction"),
        ChecklistTemplateItem(ELECTRICAL, "No exposed live parts or damaged insulation", critical = true),
        ChecklistTemplateItem(ELECTRICAL, "Circuit breakers are labelled"),
        ChecklistTemplateItem(ELECTRICAL, "No signs of overheating, burning or sparking", critical = true),
        ChecklistTemplateItem(ELECTRICAL, "Insulating rubber mat is in front of the panel"),
        ChecklistTemplateItem(ELECTRICAL, "No daisy-chained extension cords or overloaded sockets"),
    )

    private val fireAlarm = listOf(
        ChecklistTemplateItem(ALARM, "Control panel shows normal status with no faults", critical = true),
        ChecklistTemplateItem(ALARM, "Manual call point is accessible, unobstructed and undamaged", critical = true),
        ChecklistTemplateItem(ALARM, "Sounders are audible in the area (last weekly test recorded)", critical = true),
        ChecklistTemplateItem(ALARM, "Smoke/heat detectors are clean, uncovered and undamaged"),
        ChecklistTemplateItem(ALARM, "Weekly test log is up to date"),
        ChecklistTemplateItem(ALARM, "Backup battery / power indicator is normal"),
    )

    private val emergencyExit = listOf(
        ChecklistTemplateItem(EXIT, "Exit door opens outward easily and is not locked during working hours", critical = true),
        ChecklistTemplateItem(EXIT, "Exit route is clear of materials and obstructions", critical = true),
        ChecklistTemplateItem(EXIT, "Exit sign is lit and visible", critical = true),
        ChecklistTemplateItem(EXIT, "Emergency lighting works along the route"),
        ChecklistTemplateItem(EXIT, "Stairs and handrails are in good condition"),
        ChecklistTemplateItem(EXIT, "Assembly point direction signs are posted"),
    )

    private val boiler = listOf(
        ChecklistTemplateItem(BOILER, "Boiler is run only by a certified operator who is present", critical = true),
        ChecklistTemplateItem(BOILER, "Safety valve is tested and not tampered with", critical = true),
        ChecklistTemplateItem(BOILER, "Pressure and water-level gauges are readable and within limits", critical = true),
        ChecklistTemplateItem(BOILER, "Valid boiler inspection certificate is displayed"),
        ChecklistTemplateItem(BOILER, "No steam, water or fuel leaks"),
        ChecklistTemplateItem(BOILER, "Boiler room is ventilated and clear of combustible materials"),
        ChecklistTemplateItem(BOILER, "Daily operation log is up to date"),
    )

    // --- legacy checklists (unchanged from database version 1) ---------------------------------

    private val workplace = listOf(
        ChecklistTemplateItem(WORKPLACE, "Emergency exit route is clear and unobstructed", critical = true),
        ChecklistTemplateItem(WORKPLACE, "Floor is clean, dry and free of trip hazards"),
        ChecklistTemplateItem(WORKPLACE, "Workers are wearing the required PPE"),
        ChecklistTemplateItem(WORKPLACE, "First-aid kit is stocked and within reach"),
    )

    private val sewingLine = listOf(
        ChecklistTemplateItem(MACHINE, "Needle guards and finger guards are fitted on every machine", critical = true),
        ChecklistTemplateItem(MACHINE, "Pulley and belt covers are in place", critical = true),
        ChecklistTemplateItem(MACHINE, "Power cords are undamaged and not trailing across walkways"),
        ChecklistTemplateItem(MACHINE, "Lighting at each workstation is adequate"),
        ChecklistTemplateItem(STITCH, "Stitches per inch (SPI) match the tech pack specification"),
        ChecklistTemplateItem(STITCH, "Seams are free of skipped, broken or loose stitches"),
        ChecklistTemplateItem(STITCH, "Thread tension is balanced (no puckering or looping)"),
        ChecklistTemplateItem(STITCH, "No needle holes or fabric damage along the seams"),
        ChecklistTemplateItem(STITCH, "Broken-needle log is up to date and all fragments are accounted for", critical = true),
    )

    private val fireSafety = listOf(
        ChecklistTemplateItem(FIRE, "Fire extinguisher is present, charged and within its service date", critical = true),
        ChecklistTemplateItem(FIRE, "Extinguisher is mounted, signed and unobstructed"),
        ChecklistTemplateItem(FIRE, "Fire alarm call point is accessible and undamaged"),
        ChecklistTemplateItem(FIRE, "Emergency lighting and exit signs are lit", critical = true),
        ChecklistTemplateItem(FIRE, "Fire doors close fully and are not locked or wedged open", critical = true),
        ChecklistTemplateItem(FIRE, "Evacuation plan is posted and up to date"),
    )

    private val housekeeping = listOf(
        ChecklistTemplateItem(HOUSEKEEPING, "Materials are stacked safely and below sprinkler clearance"),
        ChecklistTemplateItem(HOUSEKEEPING, "Chemicals are labelled and stored in a ventilated area", critical = true),
        ChecklistTemplateItem(HOUSEKEEPING, "Fabric waste and lint are cleared regularly"),
        ChecklistTemplateItem(HOUSEKEEPING, "Aisles are marked and kept clear"),
    )

    fun forType(type: CheckpointType): List<ChecklistTemplateItem> = when (type) {
        CheckpointType.FIRE_EXTINGUISHER -> fireExtinguisher
        CheckpointType.ELECTRICAL -> electrical
        CheckpointType.FIRE_ALARM -> fireAlarm
        CheckpointType.EMERGENCY_EXIT -> emergencyExit
        CheckpointType.BOILER -> boiler
        CheckpointType.SEWING_LINE -> sewingLine + workplace
        CheckpointType.FIRE_SAFETY -> fireSafety + workplace
        CheckpointType.GENERAL_AREA -> housekeeping + workplace
    }
}
