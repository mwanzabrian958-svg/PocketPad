package com.pocketpad.data.profile

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val name: String,
    val controllerMode: String = "Xbox",
    val sensitivity: Float = 0.7f,
    val deadZone: Float = 0.15f,
    val layoutJson: String = "{}"
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY name")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE name = :name LIMIT 1")
    suspend fun getProfile(name: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE name = :name")
    suspend fun delete(name: String)
}

@Database(entities = [ProfileEntity::class], version = 1, exportSchema = true)
abstract class PocketPadDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
}
