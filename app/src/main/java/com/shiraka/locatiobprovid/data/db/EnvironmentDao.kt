package com.shiraka.locatiobprovid.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface EnvironmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(record: LocationRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnectedWifi(wifi: LocationConnectedWifi)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWifiDevice(device: WifiDevice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationWifi(record: LocationWifi)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBluetoothDevice(device: BluetoothDevice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationBluetooth(record: LocationBluetooth)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCellDevice(device: CellDevice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationCell(record: LocationCell)

    @Query("SELECT * FROM location_records")
    suspend fun getAllLocations(): List<LocationRecord>

    /**
     * Live, coordinate-only stream used by map overlays. Keeping this separate
     * from CompleteLocation avoids reloading every Wi-Fi/cell relation whenever
     * the scanner inserts a new sample.
     */
    @Query("SELECT * FROM location_records ORDER BY timestamp DESC")
    fun observeAllLocations(): Flow<List<LocationRecord>>

    /**
     * Live map source containing only coordinates with replayable RF relations.
     * Empty scan rows remain manageable/exportable but never paint coverage.
     */
    @Query("""
        SELECT lr.* FROM location_records AS lr
        WHERE EXISTS (
            SELECT 1 FROM location_connected_wifi AS cw WHERE cw.locationId = lr.id
        ) OR EXISTS (
            SELECT 1 FROM location_wifi AS lw WHERE lw.locationId = lr.id
        ) OR EXISTS (
            SELECT 1 FROM location_cells AS lc WHERE lc.locationId = lr.id
        )
        ORDER BY lr.timestamp DESC
    """)
    fun observeRfCoverageLocations(): Flow<List<LocationRecord>>

    /**
     * Coordinate/presence-only pre-filter for the shared hex policy. This keeps
     * the indexed bounds scan cheap; CompleteLocation relations are fetched
     * only after Kotlin has selected the exact target cell.
     */
    @Query("""
        SELECT lr.*,
            CASE WHEN EXISTS (
                SELECT 1 FROM location_connected_wifi AS cw WHERE cw.locationId = lr.id
            ) THEN 1 ELSE 0 END AS hasConnectedWifi,
            CASE WHEN EXISTS (
                SELECT 1 FROM location_connected_wifi AS cw WHERE cw.locationId = lr.id
            ) OR EXISTS (
                SELECT 1 FROM location_wifi AS lw WHERE lw.locationId = lr.id
            ) THEN 1 ELSE 0 END AS hasWifi,
            CASE WHEN EXISTS (
                SELECT 1 FROM location_cells AS lc WHERE lc.locationId = lr.id
            ) THEN 1 ELSE 0 END AS hasCell,
            0 AS hasBluetooth
        FROM location_records AS lr
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lng BETWEEN :minLng AND :maxLng
          AND (
            EXISTS (SELECT 1 FROM location_connected_wifi AS cw WHERE cw.locationId = lr.id)
            OR EXISTS (SELECT 1 FROM location_wifi AS lw WHERE lw.locationId = lr.id)
            OR EXISTS (SELECT 1 FROM location_cells AS lc WHERE lc.locationId = lr.id)
          )
        ORDER BY lr.timestamp DESC
    """)
    suspend fun getRfCoverageLocationsInBounds(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<RfCoverageLocation>

    @Query("""
        SELECT * FROM location_records
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lng BETWEEN :minLng AND :maxLng
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getLocationsInBounds(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        limit: Int = 1000
    ): List<LocationRecord>

    @Query("SELECT * FROM location_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLocation(): LocationRecord?

    @Transaction
    @Query("SELECT * FROM location_records")
    suspend fun getAllCompleteLocations(): List<CompleteLocation>

    @Transaction
    @Query("SELECT * FROM location_records WHERE id IN (:ids)")
    suspend fun getCompleteLocationsByIds(ids: List<Long>): List<CompleteLocation>

    @Transaction
    @Query("SELECT * FROM location_records WHERE id IN (:ids)")
    suspend fun getRuntimeRfLocationsByIds(ids: List<Long>): List<RuntimeRfLocation>

    @Query("SELECT COUNT(*) FROM location_records")
    suspend fun getRecordCount(): Int

    @Query("DELETE FROM location_records")
    suspend fun clearAll()

    @Query("DELETE FROM location_records WHERE id = :id")
    suspend fun deleteLocation(id: Long)

    @Query("DELETE FROM location_records WHERE id IN (:ids)")
    suspend fun deleteLocations(ids: List<Long>)

    @Query("UPDATE location_records SET placeName = :placeName, remark = :remark WHERE id = :id")
    suspend fun updateMetadata(id: Long, placeName: String, remark: String)
}
