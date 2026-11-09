package com.safestitch.app.ui.inspection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safestitch.app.data.AuthRepository
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.db.ChecklistItemEntity
import com.safestitch.app.data.db.InspectionWithDetails
import com.safestitch.app.data.db.PhotoEntity
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.domain.CompletionRules
import com.safestitch.app.domain.FindingDefaults
import com.safestitch.app.domain.InspectionSummary
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.domain.Priority
import com.safestitch.app.pdf.ReportService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InspectionUiState(
    val details: InspectionWithDetails,
    val sections: List<Pair<String, List<ChecklistItemEntity>>>,
    val summary: InspectionSummary,
    val photosByItem: Map<Long?, List<PhotoEntity>>,
)

class InspectionViewModel(
    private val inspectionId: Long,
    private val repository: InspectionRepository,
    private val reportService: ReportService,
    auth: AuthRepository,
) : ViewModel() {
    sealed interface Event {
        data object Completed : Event
        data object Deleted : Event
        data class Error(val message: String) : Event
    }

    val state: StateFlow<InspectionUiState?> = repository.observeInspection(inspectionId)
        .map { details ->
            details?.let {
                InspectionUiState(
                    details = it,
                    sections = it.sortedItems.groupBy { item -> item.section }.toList(),
                    summary = it.summary(),
                    photosByItem = it.sortedPhotos.filterNot { p -> p.isAfterFix }.groupBy { photo -> photo.itemId },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** People who can be made responsible for fixing a finding. */
    val people: StateFlow<List<UserEntity>> = auth.observeUsers().map { list -> list.filter { it.active } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val channel = Channel<Event>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    var completing by mutableStateOf(false)
        private set
    var completionProblems by mutableStateOf<List<String>>(emptyList())
        private set

    // Every change is saved immediately, in order, so nothing is lost if the phone dies mid-inspection.
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    channel.send(Event.Error(e.message ?: "Could not save."))
                }
            }
        }
    }

    private fun enqueue(block: suspend () -> Unit) {
        writes.trySend(block)
    }

    fun setResult(item: ChecklistItemEntity, result: ItemResult) = enqueue { repository.setItemResult(item, result) }
    fun setNote(itemId: Long, note: String) = enqueue { repository.setItemNote(itemId, note) }
    fun setResponsible(itemId: Long, person: UserEntity) = enqueue { repository.setResponsible(itemId, person.displayName, person.id) }

    /** Changing the priority resets the due date to that priority's default (pick another date afterwards if needed). */
    fun setPriority(itemId: Long, priority: Priority) = enqueue {
        repository.setFinding(itemId, priority, FindingDefaults.dueAt(priority, System.currentTimeMillis()))
    }

    fun setDueDate(item: ChecklistItemEntity, dueAt: Long) = enqueue {
        repository.setFinding(item.id, item.priority ?: FindingDefaults.priorityFor(item.critical), dueAt)
    }

    fun setGeneralNotes(notes: String) = enqueue { repository.setGeneralNotes(inspectionId, notes) }
    fun deletePhoto(photoId: Long) = enqueue { repository.deletePhoto(photoId) }
    fun dismissProblems() {
        completionProblems = emptyList()
    }

    /**
     * Validates, writes the PDF, then submits (creating one corrective action per failed item).
     * Pressing Submit again, or retrying after a crash, never creates a second inspection or action.
     */
    fun complete() {
        if (completing) return
        completing = true
        enqueue {
            try {
                val details = repository.getInspection(inspectionId) ?: return@enqueue
                if (details.isCompleted) {
                    channel.send(Event.Completed)
                    return@enqueue
                }
                val problems = CompletionRules.problems(details.items.map { it.toOutcome() }, details.inspection.inspectorName)
                if (problems.isNotEmpty()) {
                    completionProblems = problems
                    return@enqueue
                }
                val completedAt = System.currentTimeMillis()
                val file = reportService.generateReport(inspectionId, completedAt)
                val result = repository.submitInspection(inspectionId, completedAt, file.name)
                if (result.alreadySubmitted) {
                    val stored = repository.getInspection(inspectionId)?.inspection?.reportFileName
                    if (stored != file.name) reportService.deleteReport(file.name)
                }
                channel.send(Event.Completed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                channel.send(Event.Error("Could not submit: ${e.message}"))
            } finally {
                completing = false
            }
        }
    }

    fun delete() = enqueue {
        repository.deleteInspection(inspectionId)?.let(reportService::deleteReport)
        channel.send(Event.Deleted)
    }
}
