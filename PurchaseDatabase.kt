package com.monpanier.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "purchases")
data class PurchaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptDate: String,
    val store: String,
    val product: String,
    val quantity: Double,
    val unitPrice: Double,
    val total: Double,
    val currency: String,
    val receiptId: String
)

@Dao
interface PurchaseDao {
    @Query("SELECT * FROM purchases ORDER BY id DESC") fun observeAll(): Flow<List<PurchaseEntity>>
    @Insert suspend fun insertAll(items: List<PurchaseEntity>)
    @Insert suspend fun insert(item: PurchaseEntity)
    @Query("DELETE FROM purchases WHERE receiptId = :receiptId") suspend fun deleteReceipt(receiptId: String)
    @Query("SELECT * FROM purchases") suspend fun getAll(): List<PurchaseEntity>
    @Query("DELETE FROM purchases") suspend fun clearAll()
}

@Database(entities = [PurchaseEntity::class], version = 1, exportSchema = false)
abstract class PurchaseDatabase : RoomDatabase() {
    abstract fun purchaseDao(): PurchaseDao
    companion object {
        @Volatile private var INSTANCE: PurchaseDatabase? = null
        fun get(context: Context): PurchaseDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, PurchaseDatabase::class.java, "mon_panier.db").build().also { INSTANCE = it }
        }
    }
}
