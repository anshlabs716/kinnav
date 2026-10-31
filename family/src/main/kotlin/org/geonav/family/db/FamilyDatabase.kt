package org.geonav.family.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Local persistence for the sharing subsystem.
 *
 * Ported from Luogo-FOSS's LuogoDatabase and trimmed to the three tables sharing needs:
 * the local profile (master switch + visibility radius), the groups you share with, and
 * group membership. Items, geofence history, offline regions and tracker alerts from the
 * parent app were not ported.
 *
 * Enums are stored as their `name` string rather than ordinals, so reordering an enum in
 * Kotlin can never silently reinterpret existing rows.
 */

@Entity(tableName = "users")
data class UserProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val colorArgb: Long,
    val publicKeyBase64: String,
    // Opt-in by default: a fresh install must never share anything.
    val sharingEnabled: Boolean = false,
    val temporarySharingUntilMs: Long? = null,
    val visibilityRadiusMeters: Int = 0,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "groups", indices = [Index("ownerId")])
data class PeerGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val ownerId: String,
    val createdAtMs: Long,
    val memberCount: Int = 1,
    val shareMyLocation: Boolean = true,
    val temporarySharingUntilMs: Long? = null
)

@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "userId"],
    indices = [Index("userId")]
)
data class GroupMemberEntity(
    val groupId: String,
    val userId: String,
    val displayName: String,
    val colorArgb: Long,
    val isOwner: Boolean = false,
    val joinedAtMs: Long = System.currentTimeMillis()
)

@Dao
interface FamilyDao {

    // -------------------------------------------------------------- profiles

    @Upsert
    suspend fun upsertUserProfile(profile: UserProfileEntity)

    @Query("SELECT * FROM users LIMIT 1")
    fun observeLocalUser(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM users LIMIT 1")
    suspend fun getLocalUser(): UserProfileEntity?

    // ---------------------------------------------------------------- groups

    @Upsert
    suspend fun upsertGroup(group: PeerGroupEntity)

    @Query("SELECT * FROM groups ORDER BY createdAtMs ASC")
    fun observeGroups(): Flow<List<PeerGroupEntity>>

    @Query("SELECT * FROM groups ORDER BY createdAtMs ASC")
    suspend fun getGroups(): List<PeerGroupEntity>

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun deleteGroup(id: String)

    @Upsert
    suspend fun upsertGroupMember(member: GroupMemberEntity)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND userId = :userId")
    suspend fun removeGroupMember(groupId: String, userId: String)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getGroupMembers(groupId: String): List<GroupMemberEntity>
}

@Database(
    entities = [
        UserProfileEntity::class,
        PeerGroupEntity::class,
        GroupMemberEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class FamilyDatabase : RoomDatabase() {

    abstract fun familyDao(): FamilyDao

    companion object {
        private const val DB_NAME = "kinnav_family.db"

        @Volatile
        private var instance: FamilyDatabase? = null

        fun getInstance(context: android.content.Context): FamilyDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: android.content.Context): FamilyDatabase =
            Room.databaseBuilder(context, FamilyDatabase::class.java, DB_NAME)
                // No destructive fallback: sharing state must survive an upgrade. Future
                // versions must ship a real Migration.
                .build()

        /** Test seam so tests can install an in-memory database. */
        fun setInstanceForTesting(database: FamilyDatabase?) {
            instance = database
        }
    }
}
