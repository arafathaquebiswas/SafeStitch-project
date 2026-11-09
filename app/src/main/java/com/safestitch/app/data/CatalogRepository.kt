package com.safestitch.app.data

import androidx.room.withTransaction
import com.safestitch.app.data.db.AreaEntity
import com.safestitch.app.data.db.AreaPath
import com.safestitch.app.data.db.BuildingEntity
import com.safestitch.app.data.db.FactoryEntity
import com.safestitch.app.data.db.FloorEntity
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.data.db.TemplateQuestionEntity
import com.safestitch.app.domain.CheckpointType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Level in the location hierarchy, used by the generic Locations screen. */
enum class LocationLevel(val label: String, val plural: String) {
    FACTORY("Factory", "Factories"),
    BUILDING("Building", "Buildings"),
    FLOOR("Floor", "Floors"),
    AREA("Area", "Areas"),
    ;

    val child: LocationLevel? get() = entries.getOrNull(ordinal + 1)
}

data class LocationNode(val id: Long, val name: String)

/** Admin-managed reference data: the factory/building/floor/area hierarchy and the editable checklist questions. */
class CatalogRepository(private val database: SafeStitchDatabase) {
    private val locations = database.locationDao()
    private val questions = database.templateQuestionDao()

    // --- locations --------------------------------------------------------------------------------

    fun observeChildren(level: LocationLevel, parentId: Long): Flow<List<LocationNode>> = when (level) {
        LocationLevel.FACTORY -> locations.observeFactories().map { list -> list.map { LocationNode(it.id, it.name) } }
        LocationLevel.BUILDING -> locations.observeBuildings(parentId).map { list -> list.map { LocationNode(it.id, it.name) } }
        LocationLevel.FLOOR -> locations.observeFloors(parentId).map { list -> list.map { LocationNode(it.id, it.name) } }
        LocationLevel.AREA -> locations.observeAreas(parentId).map { list -> list.map { LocationNode(it.id, it.name) } }
    }

    fun observeAreaPaths(): Flow<List<AreaPath>> = locations.observeAreaPaths()
    suspend fun getAreaPaths(): List<AreaPath> = locations.getAreaPaths()

    suspend fun addLocation(level: LocationLevel, parentId: Long, name: String): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Enter a name." }
        return when (level) {
            LocationLevel.FACTORY -> locations.insertFactory(FactoryEntity(name = clean))
            LocationLevel.BUILDING -> locations.insertBuilding(BuildingEntity(factoryId = parentId, name = clean))
            LocationLevel.FLOOR -> locations.insertFloor(FloorEntity(buildingId = parentId, name = clean))
            LocationLevel.AREA -> locations.insertArea(AreaEntity(floorId = parentId, name = clean))
        }
    }

    suspend fun renameLocation(level: LocationLevel, id: Long, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Enter a name." }
        when (level) {
            LocationLevel.FACTORY -> locations.renameFactory(id, clean)
            LocationLevel.BUILDING -> locations.renameBuilding(id, clean)
            LocationLevel.FLOOR -> locations.renameFloor(id, clean)
            LocationLevel.AREA -> locations.renameArea(id, clean)
        }
    }

    // --- checklist questions (changes apply to NEW inspections only) -----------------------------

    fun observeQuestions(type: CheckpointType): Flow<List<TemplateQuestionEntity>> = questions.observeForType(type)

    suspend fun addQuestion(type: CheckpointType, section: String, question: String, critical: Boolean): Long {
        require(section.isNotBlank() && question.isNotBlank()) { "Enter a section and a question." }
        return database.withTransaction {
            questions.insert(
                TemplateQuestionEntity(
                    type = type,
                    position = questions.maxPosition(type) + 1,
                    section = section.trim(),
                    question = question.trim(),
                    critical = critical,
                ),
            )
        }
    }

    suspend fun updateQuestion(question: TemplateQuestionEntity) {
        require(question.section.isNotBlank() && question.question.isNotBlank()) { "Enter a section and a question." }
        questions.update(question.copy(section = question.section.trim(), question = question.question.trim()))
    }

    /** Moves a question one place up or down within its equipment type. */
    suspend fun moveQuestion(question: TemplateQuestionEntity, up: Boolean) = database.withTransaction {
        val list = questions.allForType(question.type).toMutableList()
        val index = list.indexOfFirst { it.id == question.id }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in list.indices) return@withTransaction
        list.add(target, list.removeAt(index))
        questions.updateAll(list.mapIndexed { position, item -> item.copy(position = position) })
    }
}
