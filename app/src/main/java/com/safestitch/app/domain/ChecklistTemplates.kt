package com.safestitch.app.domain

data class ChecklistTemplateItem(
    val section: String,
    val question: String,
    val critical: Boolean = false,
)

/**
 * Built-in garment-factory checklists. Items are copied into each new inspection, so
 * editing these later never changes inspections that were already recorded.
 */
object ChecklistTemplates {
    const val WORKPLACE = "Workplace safety"
    const val MACHINE = "Machine safety"
    const val STITCH = "Stitch quality"
    const val FIRE = "Fire protection"
    const val ELECTRICAL = "Electrical safety"
    const val HOUSEKEEPING = "Housekeeping & storage"

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
        ChecklistTemplateItem(
            STITCH,
            "Broken-needle log is up to date and all fragments are accounted for",
            critical = true,
        ),
    )

    private val fireSafety = listOf(
        ChecklistTemplateItem(FIRE, "Fire extinguisher is present, charged and within its service date", critical = true),
        ChecklistTemplateItem(FIRE, "Extinguisher is mounted, signed and unobstructed"),
        ChecklistTemplateItem(FIRE, "Fire alarm call point is accessible and undamaged"),
        ChecklistTemplateItem(FIRE, "Emergency lighting and exit signs are lit", critical = true),
        ChecklistTemplateItem(FIRE, "Fire doors close fully and are not locked or wedged open", critical = true),
        ChecklistTemplateItem(FIRE, "Evacuation plan is posted and up to date"),
    )

    private val electrical = listOf(
        ChecklistTemplateItem(ELECTRICAL, "Panel door is closed, labelled and free of obstruction"),
        ChecklistTemplateItem(ELECTRICAL, "No exposed live parts or damaged insulation", critical = true),
        ChecklistTemplateItem(ELECTRICAL, "Circuit breakers are labelled"),
        ChecklistTemplateItem(ELECTRICAL, "No signs of overheating, burning or sparking", critical = true),
        ChecklistTemplateItem(ELECTRICAL, "Insulating rubber mat is in front of the panel"),
        ChecklistTemplateItem(ELECTRICAL, "No daisy-chained extension cords or overloaded sockets"),
    )

    private val housekeeping = listOf(
        ChecklistTemplateItem(HOUSEKEEPING, "Materials are stacked safely and below sprinkler clearance"),
        ChecklistTemplateItem(HOUSEKEEPING, "Chemicals are labelled and stored in a ventilated area", critical = true),
        ChecklistTemplateItem(HOUSEKEEPING, "Fabric waste and lint are cleared regularly"),
        ChecklistTemplateItem(HOUSEKEEPING, "Aisles are marked and kept clear"),
    )

    fun forType(type: CheckpointType): List<ChecklistTemplateItem> = when (type) {
        CheckpointType.SEWING_LINE -> sewingLine
        CheckpointType.FIRE_SAFETY -> fireSafety
        CheckpointType.ELECTRICAL -> electrical
        CheckpointType.GENERAL_AREA -> housekeeping
    } + workplace
}
