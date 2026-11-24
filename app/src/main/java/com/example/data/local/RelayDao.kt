package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class NumberSummaryRow(
    val id: String,
    val e164: String,
    val countryCode: String,
    val countryName: String,
    val label: String,
    val carrier: String,
    val providerType: String,
    val dayCount: Int,
    val lastAtEpochMs: Long?,
    val preview: String?,
    val senderName: String?,
    val isTemp: Boolean,
    val tempMode: String?,
    val tempSourceType: String?,
    val tempResolvedSource: String?,
    val tempCustomSourceId: Long?,
    val tempStatus: String?,
    val tempLastError: String?,
    val tempLastSyncEpochMs: Long?,
    val tempNotes: String?,
    val isPinned: Boolean,
    val isMuted: Boolean,
)

data class TempNumberDetailRow(
    val numberId: String,
    val e164: String,
    val mode: String,
    val sourceType: String,
    val resolvedSource: String?,
    val customSourceId: Long?,
    val customSourceName: String?,
    val label: String,
    val notes: String,
    val lastSyncEpochMs: Long?,
    val status: String,
    val lastError: String?,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val createdAtEpochMs: Long,
    val messageCount: Int,
)

data class MessageTimestampRow(
    val numberId: String,
    val receivedAtEpochMs: Long,
)

@Dao
interface RelayDao {

    @Query(
        """
        SELECT
            n.id,
            n.e164,
            n.countryCode,
            n.countryName,
            n.label,
            n.carrier,
            n.providerType,
            (SELECT COUNT(*) FROM messages m WHERE m.numberId = n.id AND m.receivedAtEpochMs > :dayCutoffMs) AS dayCount,
            (SELECT MAX(m.receivedAtEpochMs) FROM messages m WHERE m.numberId = n.id) AS lastAtEpochMs,
            (SELECT m.body FROM messages m WHERE m.numberId = n.id ORDER BY m.receivedAtEpochMs DESC, m.id DESC LIMIT 1) AS preview,
            (SELECT m.senderName FROM messages m WHERE m.numberId = n.id ORDER BY m.receivedAtEpochMs DESC, m.id DESC LIMIT 1) AS senderName,
            CASE WHEN t.numberId IS NOT NULL THEN 1 ELSE 0 END AS isTemp,
            t.mode AS tempMode,
            t.sourceType AS tempSourceType,
            t.resolvedSource AS tempResolvedSource,
            t.customSourceId AS tempCustomSourceId,
            t.status AS tempStatus,
            t.lastError AS tempLastError,
            t.lastSyncEpochMs AS tempLastSyncEpochMs,
            t.notes AS tempNotes,
            COALESCE(t.isPinned, 0) AS isPinned,
            COALESCE(t.isMuted, 0) AS isMuted
        FROM numbers n
        LEFT JOIN temp_numbers t ON t.numberId = n.id
        WHERE n.active = 1
        ORDER BY COALESCE(t.isPinned, 0) DESC, (n.countryCode = 'AU') DESC, lastAtEpochMs DESC, n.label ASC
        """
    )
    fun observeNumberSummaries(dayCutoffMs: Long): Flow<List<NumberSummaryRow>>

    @Query(
        """
        SELECT
            n.id,
            n.e164,
            n.countryCode,
            n.countryName,
            n.label,
            n.carrier,
            n.providerType,
            (SELECT COUNT(*) FROM messages m WHERE m.numberId = n.id AND m.receivedAtEpochMs > :dayCutoffMs) AS dayCount,
            (SELECT MAX(m.receivedAtEpochMs) FROM messages m WHERE m.numberId = n.id) AS lastAtEpochMs,
            (SELECT m.body FROM messages m WHERE m.numberId = n.id ORDER BY m.receivedAtEpochMs DESC, m.id DESC LIMIT 1) AS preview,
            (SELECT m.senderName FROM messages m WHERE m.numberId = n.id ORDER BY m.receivedAtEpochMs DESC, m.id DESC LIMIT 1) AS senderName,
            CASE WHEN t.numberId IS NOT NULL THEN 1 ELSE 0 END AS isTemp,
            t.mode AS tempMode,
            t.sourceType AS tempSourceType,
            t.resolvedSource AS tempResolvedSource,
            t.customSourceId AS tempCustomSourceId,
            t.status AS tempStatus,
            t.lastError AS tempLastError,
            t.lastSyncEpochMs AS tempLastSyncEpochMs,
            t.notes AS tempNotes,
            COALESCE(t.isPinned, 0) AS isPinned,
            COALESCE(t.isMuted, 0) AS isMuted
        FROM numbers n
        LEFT JOIN temp_numbers t ON t.numberId = n.id
        WHERE n.id = :id AND n.active = 1
        LIMIT 1
        """
    )
    fun observeNumberSummary(id: String, dayCutoffMs: Long): Flow<NumberSummaryRow?>

    @Query(
        """
        SELECT
            t.numberId,
            t.e164,
            t.mode,
            t.sourceType,
            t.resolvedSource,
            t.customSourceId,
            cs.name AS customSourceName,
            t.label,
            t.notes,
            t.lastSyncEpochMs,
            t.status,
            t.lastError,
            t.isPinned,
            t.isMuted,
            t.createdAtEpochMs,
            (SELECT COUNT(*) FROM messages m WHERE m.numberId = t.numberId) AS messageCount
        FROM temp_numbers t
        LEFT JOIN custom_sources cs ON cs.id = t.customSourceId
        ORDER BY t.isPinned DESC, t.createdAtEpochMs DESC
        """
    )
    fun observeTempNumbers(): Flow<List<TempNumberDetailRow>>

    @Query("SELECT * FROM temp_numbers WHERE numberId = :numberId LIMIT 1")
    suspend fun getTempNumberById(numberId: String): TempNumberEntity?

    @Query("SELECT * FROM temp_numbers WHERE mode = 'AUTO'")
    suspend fun getAutoTempNumbers(): List<TempNumberEntity>

    @Query("SELECT COUNT(*) FROM temp_numbers WHERE mode = 'AUTO'")
    suspend fun getAutoTempNumberCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTempNumber(temp: TempNumberEntity)

    @Query(
        """
        UPDATE temp_numbers
        SET status = :status,
            lastError = :lastError,
            lastSyncEpochMs = COALESCE(:lastSyncEpochMs, lastSyncEpochMs),
            resolvedSource = COALESCE(:resolvedSource, resolvedSource)
        WHERE numberId = :numberId
        """
    )
    suspend fun updateTempSyncState(
        numberId: String,
        status: String,
        lastError: String?,
        lastSyncEpochMs: Long?,
        resolvedSource: String?,
    )

    @Query("UPDATE temp_numbers SET isPinned = :pinned WHERE numberId = :numberId")
    suspend fun setTempPinned(numberId: String, pinned: Boolean)

    @Query("UPDATE temp_numbers SET isMuted = :muted WHERE numberId = :numberId")
    suspend fun setTempMuted(numberId: String, muted: Boolean)

    @Query(
        """
        UPDATE temp_numbers
        SET label = :label,
            notes = :notes,
            mode = :mode,
            sourceType = :sourceType,
            customSourceId = :customSourceId,
            status = :status,
            lastError = :lastError
        WHERE numberId = :numberId
        """
    )
    suspend fun updateTempMetadata(
        numberId: String,
        label: String,
        notes: String,
        mode: String,
        sourceType: String,
        customSourceId: Long?,
        status: String,
        lastError: String?,
    )

    @Query("UPDATE numbers SET label = :label, carrier = :carrier, providerType = :providerType WHERE id = :id")
    suspend fun updateNumberMetadata(id: String, label: String, carrier: String, providerType: String)

    @Query("SELECT * FROM custom_sources ORDER BY createdAtEpochMs DESC")
    fun observeCustomSources(): Flow<List<CustomSourceEntity>>

    @Query("SELECT * FROM custom_sources WHERE id = :id LIMIT 1")
    suspend fun getCustomSourceById(id: Long): CustomSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomSource(source: CustomSourceEntity): Long

    @Query("DELETE FROM custom_sources WHERE id = :id")
    suspend fun deleteCustomSource(id: Long)

    @Query("UPDATE temp_numbers SET customSourceId = NULL WHERE customSourceId = :sourceId")
    suspend fun detachCustomSourceFromTempNumbers(sourceId: Long)

    @Query(
        """
        SELECT * FROM messages
        WHERE numberId = :numberId
        ORDER BY receivedAtEpochMs DESC, id DESC
        LIMIT 200
        """
    )
    fun observeMessagesForNumber(numberId: String): Flow<List<MessageEntity>>

    @Query("SELECT numberId, receivedAtEpochMs FROM messages")
    fun observeAllMessageTimestamps(): Flow<List<MessageTimestampRow>>

    @Query("SELECT COUNT(*) FROM numbers WHERE active = 1")
    fun observeActiveNumberCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM numbers")
    suspend fun getTotalNumberCount(): Int

    @Query("SELECT * FROM numbers WHERE id = :id AND active = 1 LIMIT 1")
    suspend fun getActiveNumberById(id: String): NumberEntity?

    @Query("SELECT id FROM numbers WHERE id = :id LIMIT 1")
    suspend fun findNumberId(id: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNumbersIgnore(numbers: List<NumberEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNumber(number: NumberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessageById(id: Long)

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE numberId = :numberId
          AND dedupHash != ''
          AND dedupHash = :dedupHash
        """
    )
    suspend fun countByDedupHash(numberId: String, dedupHash: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE numberId = :numberId
          AND senderName = :senderName
          AND body = :body
          AND receivedAtEpochMs BETWEEN :fromEpochMs AND :toEpochMs
        """
    )
    suspend fun countDuplicateMessage(
        numberId: String,
        senderName: String,
        body: String,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE numberId = :numberId
          AND LOWER(TRIM(senderName)) = LOWER(TRIM(:senderName))
          AND LOWER(TRIM(body)) = LOWER(TRIM(:body))
          AND receivedAtEpochMs >= :sinceEpochMs
        """
    )
    suspend fun countDuplicateByContent(
        numberId: String,
        senderName: String,
        body: String,
        sinceEpochMs: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE numberId = :numberId
          AND source = :source
          AND receivedAtEpochMs > :sinceEpochMs
        """
    )
    suspend fun countRecentBySource(
        numberId: String,
        source: String,
        sinceEpochMs: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE numberId = :numberId
          AND receivedAtEpochMs > :sinceEpochMs
        """
    )
    suspend fun countRecentAll(
        numberId: String,
        sinceEpochMs: Long,
    ): Int

    /**
     * Prunes messages older than 48h while explicitly keeping history for
     * MANUAL and PINNED temporary numbers indefinitely until user deletion.
     */
    @Query(
        """
        DELETE FROM messages
        WHERE receivedAtEpochMs < :cutoffEpochMs
          AND isManual = 0
          AND numberId NOT IN (
              SELECT numberId FROM temp_numbers
              WHERE mode = 'MANUAL' OR isPinned = 1
          )
        """
    )
    suspend fun pruneMessagesOlderThan(cutoffEpochMs: Long): Int

    @Query("DELETE FROM numbers WHERE id IN (:ids)")
    suspend fun deleteNumbersByIds(ids: List<String>): Int
}
