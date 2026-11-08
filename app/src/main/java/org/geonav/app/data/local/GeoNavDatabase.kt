package org.geonav.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.geonav.app.data.model.OfflineDownloadProfile
import org.geonav.app.data.model.OfflineRegion
import org.geonav.app.data.model.OfflineRegionStatus
import org.geonav.app.data.model.RegionType

@Database(
    entities = [
        SavedPlaceEntity::class,
        SearchHistoryEntity::class,
        OfflineRegionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class GeoNavDatabase : RoomDatabase() {
    abstract fun savedPlacesDao(): SavedPlacesDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun offlineRegionsDao(): OfflineRegionsDao

    companion object {
        @Volatile
        private var INSTANCE: GeoNavDatabase? = null

        fun getDatabase(context: Context): GeoNavDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GeoNavDatabase::class.java,
                    "geonav_database"
                )
                .fallbackToDestructiveMigration()
                .addCallback(DatabaseCallback())
                .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // Seed initial offline region packages for realistic offline foundation
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        populateInitialData(database)
                    }
                }
            }

            private suspend fun populateInitialData(database: GeoNavDatabase) {
                // Seed only the *catalogue*: every region starts NOT_DOWNLOADED with no
                // bytes on device. Nothing here may claim a download that never happened —
                // real status and sizes are written by OfflineRepository after a real
                // transfer from the configured server.
                val sampleRegions = listOf(
                    OfflineRegion(
                        id = "region_wa",
                        name = "Western Australia",
                        country = "Australia",
                        regionType = RegionType.STATE_PROVINCE,
                        minLat = -35.2,
                        minLon = 112.9,
                        maxLat = -13.7,
                        maxLon = 129.0,
                        sizeBytes = 0,
                        lastUpdatedDate = "Not downloaded",
                        status = OfflineRegionStatus.NOT_DOWNLOADED,
                        downloadProgressPercent = 0,
                        profile = OfflineDownloadProfile.DETAILED
                    ),
                    OfflineRegion(
                        id = "region_nsw",
                        name = "New South Wales",
                        country = "Australia",
                        regionType = RegionType.STATE_PROVINCE,
                        minLat = -37.5,
                        minLon = 141.0,
                        maxLat = -28.1,
                        maxLon = 153.6,
                        sizeBytes = 0,
                        lastUpdatedDate = "Not downloaded",
                        status = OfflineRegionStatus.NOT_DOWNLOADED,
                        downloadProgressPercent = 0,
                        profile = OfflineDownloadProfile.COMPLETE
                    ),
                    OfflineRegion(
                        id = "region_california",
                        name = "California",
                        country = "United States",
                        regionType = RegionType.STATE_PROVINCE,
                        minLat = 32.5,
                        minLon = -124.4,
                        maxLat = 42.0,
                        maxLon = -114.1,
                        sizeBytes = 0,
                        lastUpdatedDate = "Not downloaded",
                        status = OfflineRegionStatus.NOT_DOWNLOADED,
                        downloadProgressPercent = 0,
                        profile = OfflineDownloadProfile.DETAILED
                    ),
                    OfflineRegion(
                        id = "region_london",
                        name = "Greater London & South East",
                        country = "United Kingdom",
                        regionType = RegionType.CITY,
                        minLat = 50.8,
                        minLon = -0.5,
                        maxLat = 51.7,
                        maxLon = 0.3,
                        sizeBytes = 0,
                        lastUpdatedDate = "Not downloaded",
                        status = OfflineRegionStatus.NOT_DOWNLOADED,
                        downloadProgressPercent = 0,
                        profile = OfflineDownloadProfile.COMPLETE
                    )
                )
                database.offlineRegionsDao().insertAll(
                    sampleRegions.map { OfflineRegionEntity.fromDomain(it) }
                )
            }
        }
    }
}
