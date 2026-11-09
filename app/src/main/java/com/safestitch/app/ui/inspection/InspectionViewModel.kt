package com.safestitch.app.ui.inspection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.SettingsRepository
import com.safestitch.app.data.db.ChecklistItemEntity
import com.safestitch.app.data.db.InspectionWithDetails
import com.safestitch.app.data.db.PhotoEntity
import com.safestitch.app.domain.CompletionRules
import com.safestitch.app.domain.InspectionSummary
import com.safestitch.app.domain.ItemResult
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
    private val settings: SettingsRepository,
    private val reportService: ReportService,
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
                    photosByItem = it.sortedPhotos.groupBy { photo -> photo.itemId },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
                    channel.send(Event.Error("Could not save: ${e.message}"))
                }
            }
        }
    }

    private fun enqueue(block: suspend () -> Unit) {
        writes.trySend(block)
    }

    fun setResult(itemId: Long, result: ItemResult) = enqueue { repository.setItemResult(itemId, result) }
    fun setNote(itemId: Long, note: String) = enqueue { repository.setItemNote(itemId, note) }
    fun setHeader(inspectorName: String, siteName: String) = enqueue { repository.updateHeader(inspectionId, inspectorName, siteName) }
    fun setGeneralNotes(notes: String) = enqueue { repository.setGeneralNotes(inspectionId, notes) }
    fun deletePhoto(photoId: Long) = enqueue { repository.deletePhoto(photoId) }
    fun dismissProblems() {
        completionProblems = emptyList()
    }

    /** Validates, writes the PDF and marks the inspection completed (after all pending saves). */
    fun complete() {
        if (completing) return
        completing = true
        enqueue {
            try {
                val details = repository.getInspection(inspectionId) ?: return@enqueue
                val problems = CompletionRules.problems(details.items.map { it.toOutcome() }, details.inspection.inspectorName)
                if (problems.isNotEmpty()) {
                    completionProblems = problems
                    return@enqueue
                }
                val completedAt = System.currentTimeMillis()
                val file = reportService.generateReport(inspectionId, completedAt)
                repository.markCompleted(inspectionId, completedAt, file.name)
                if (settings.settings.value.inspectorName.isBlank()) {
                    settings.update(details.inspection.inspectorName, details.inspection.siteName)
                }
                channel.send(Event.Completed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                channel.send(Event.Error("Could not create the PDF: ${e.message}"))
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
