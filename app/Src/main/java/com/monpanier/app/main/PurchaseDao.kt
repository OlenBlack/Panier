
package com.monpanier.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PurchaseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(purchases: List<PurchaseEntity>)

    @Query("SELECT * FROM purchases ORDER BY id DESC")
    fun observeAll(): Flow<List<PurchaseEntity>>

    @Query("DELETE FROM purchases")
    suspend fun deleteAll()
}
