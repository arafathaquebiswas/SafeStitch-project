package com.safestitch.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * Room database entity representing a camera-captured inspection record with its capture
 * [timestamp], local [imagePath] from camera capture, and [statusTags] to organize and filter
 * captured inspection photos.
 */
@Entity(
    tableName = "captured_inspections",
    indices = [Index("timestamp")],
)
data class Inspection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String,
    @ColumnInfo(defaultValue = "") val statusTags: List<String> = listOf(TAG_NEEDS_REVIEW),
) {
    constructor(
        id: Long = 0,
        timestamp: Long = System.currentTimeMillis(),
        imagePath: String,
        statusTags: String,
    ) : this(
        id = id,
        timestamp = timestamp,
        imagePath = imagePath,
        statusTags = StatusTagConverters.toStatusTagList(statusTags),
    )

    /** Alias for [imagePath] for callers referencing `imageFilePath`. */
    val imageFilePath: String
        get() = imagePath

    /** Cleaned, non-empty status tags attached to this captured inspection photo. */
    val normalizedTags: List<String>
        get() = statusTags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun hasTag(tag: String): Boolean =
        normalizedTags.any { it.equals(tag.trim(), ignoreCase = true) }

    fun withToggledTag(tag: String): Inspection {
        val normalized = normalizeTag(tag)
        if (normalized.isEmpty()) return this
        val updated = if (hasTag(normalized)) {
            normalizedTags.filterNot { it.equals(normalized, ignoreCase = true) }
        } else {
            normalizedTags + normalized
        }
        return copy(statusTags = updated.ifEmpty { listOf(TAG_NEEDS_REVIEW) })
    }

    fun withAddedTag(rawTag: String): Inspection {
        val normalized = normalizeTag(rawTag)
        if (normalized.isEmpty() || hasTag(normalized)) return this
        return copy(statusTags = normalizedTags + normalized)
    }

    fun withRemovedTag(rawTag: String): Inspection {
        val normalized = normalizeTag(rawTag)
        if (normalized.isEmpty()) return this
        val remaining = normalizedTags.filterNot { it.equals(normalized, ignoreCase = true) }
        return copy(statusTags = remaining.ifEmpty { listOf(TAG_NEEDS_REVIEW) })
    }

    companion object {
        const val TAG_NEEDS_REVIEW = "NEEDS_REVIEW"
        const val TAG_PASS = "PASS"
        const val TAG_FAIL = "FAIL"
        const val TAG_FOLLOW_UP = "FOLLOW_UP"

        val DEFAULT_STATUS_TAGS: List<String> = listOf(
            TAG_NEEDS_REVIEW,
            TAG_PASS,
            TAG_FAIL,
            TAG_FOLLOW_UP,
        )

        fun normalizeTag(raw: String): String =
            raw.trim()
                .replace(Regex("\\s+"), "_")
                .replace(",", "")
                .uppercase()
    }
}

/**
 * Room type converters for storing [Inspection.statusTags] as a comma-separated string column.
 */
object StatusTagConverters {
    @TypeConverter
    @JvmStatic
    fun fromStatusTagList(tags: List<String>?): String =
        tags.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")

    @TypeConverter
    @JvmStatic
    fun toStatusTagList(raw: String?): List<String> =
        raw.orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
