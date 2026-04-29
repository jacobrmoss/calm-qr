package com.caravanfire.calmqr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_codes")
data class SavedCode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val content: String,
    val format: String,
    val timestamp: Long = System.currentTimeMillis(),
    val qrImageData: ByteArray? = null,
    val createdAt: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SavedCode) return false
        return id == other.id && name == other.name && content == other.content &&
                format == other.format && timestamp == other.timestamp &&
                qrImageData.contentEquals(other.qrImageData) &&
                createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (qrImageData?.contentHashCode() ?: 0)
        result = 31 * result + (createdAt?.hashCode() ?: 0)
        return result
    }
}
