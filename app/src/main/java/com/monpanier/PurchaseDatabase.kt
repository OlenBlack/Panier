
package com.monpanier.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "purchases")
data class PurchaseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val receiptDate: String,
    val store: String,
    val product: String,
    val quantity: Double,
    val unitPrice: Double,
    val total: Double,
    val currency: String,
    @ColumnInfo(defaultValue = "''")
    val ticketNumber: String = "",
    val receiptId: String
)

@Entity(
    tableName = "stores",
    indices = [Index(value = ["name"], unique = true)]
)
data class StoreEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String
)

@Entity(
    tableName = "monthly_budgets",
    indices = [Index(value = ["month"], unique = true)]
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val month: String,
    val amount: Double
)

@Entity(tableName = "shopping_list")
data class ShoppingListEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val product: String,
    val quantity: Double,
    val currency: String,
    val store: String = "",
    val estimatedUnitPrice: Double? = null,
    val priceDate: String = "",
    val checked: Boolean = false
)

@Dao
interface PurchaseDao {

    // ACHATS

    @Query("SELECT * FROM purchases ORDER BY id DESC")
    fun observeAll(): Flow<List<PurchaseEntity>>

    @Insert
    suspend fun insertAll(items: List<PurchaseEntity>)

    @Insert
    suspend fun insert(item: PurchaseEntity)

    @Update
    suspend fun update(item: PurchaseEntity)

    @Query("DELETE FROM purchases WHERE receiptId = :receiptId")
    suspend fun deleteReceipt(receiptId: String)

    @Query("DELETE FROM purchases WHERE id = :purchaseId")
    suspend fun deletePurchase(purchaseId: Long)

    @Query("SELECT * FROM purchases ORDER BY id ASC")
    suspend fun getAll(): List<PurchaseEntity>

    @Query("DELETE FROM purchases")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restorePurchases(items: List<PurchaseEntity>)

    @Query("""
        SELECT * FROM purchases
        WHERE ticketNumber LIKE '%' || :number || '%'
        ORDER BY id DESC
    """)
    suspend fun searchByTicketNumber(number: String): List<PurchaseEntity>

    // MAGASINS

    @Query("SELECT * FROM stores ORDER BY name COLLATE NOCASE")
    fun observeStores(): Flow<List<StoreEntity>>

    @Query("SELECT * FROM stores ORDER BY id ASC")
    suspend fun getAllStores(): List<StoreEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStoreIfAbsent(store: StoreEntity): Long

    @Update
    suspend fun updateStore(store: StoreEntity)

    @Query("DELETE FROM stores WHERE id = :storeId")
    suspend fun deleteStore(storeId: Long)

    @Query("DELETE FROM stores")
    suspend fun clearStores()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreStores(items: List<StoreEntity>)

    // BUDGETS

    @Query("SELECT * FROM monthly_budgets ORDER BY month DESC")
    fun observeBudgets(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM monthly_budgets ORDER BY id ASC")
    suspend fun getAllBudgets(): List<BudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBudget(budget: BudgetEntity)

    @Query("DELETE FROM monthly_budgets WHERE id = :budgetId")
    suspend fun deleteBudget(budgetId: Long)

    @Query("DELETE FROM monthly_budgets")
    suspend fun clearBudgets()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreBudgets(items: List<BudgetEntity>)

    // LISTE DE COURSES

    @Query("SELECT * FROM shopping_list ORDER BY store COLLATE NOCASE, product COLLATE NOCASE")
    fun observeShoppingList(): Flow<List<ShoppingListEntity>>

    @Query("SELECT * FROM shopping_list ORDER BY id ASC")
    suspend fun getAllShoppingItems(): List<ShoppingListEntity>

    @Insert
    suspend fun insertShoppingItem(item: ShoppingListEntity)

    @Update
    suspend fun updateShoppingItem(item: ShoppingListEntity)

    @Query("DELETE FROM shopping_list WHERE id = :itemId")
    suspend fun deleteShoppingItem(itemId: Long)

    @Query("DELETE FROM shopping_list")
    suspend fun clearShoppingList()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreShoppingItems(items: List<ShoppingListEntity>)
}

@Database(
    entities = [
        PurchaseEntity::class,
        StoreEntity::class,
        BudgetEntity::class,
        ShoppingListEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class PurchaseDatabase : RoomDatabase() {

    abstract fun purchaseDao(): PurchaseDao

    companion object {

        @Volatile
        private var INSTANCE: PurchaseDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE purchases ADD COLUMN ticketNumber TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS stores (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_stores_name
                    ON stores(name)
                """)
                db.execSQL("""
                    INSERT OR IGNORE INTO stores(name)
                    SELECT DISTINCT TRIM(store)
                    FROM purchases
                    WHERE TRIM(store) <> ''
                """)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS monthly_budgets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        month TEXT NOT NULL,
                        amount REAL NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_monthly_budgets_month
                    ON monthly_budgets(month)
                """)
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS shopping_list (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        product TEXT NOT NULL,
                        quantity REAL NOT NULL,
                        currency TEXT NOT NULL,
                        store TEXT NOT NULL,
                        estimatedUnitPrice REAL,
                        priceDate TEXT NOT NULL,
                        checked INTEGER NOT NULL
                    )
                """)
            }
        }

        fun get(context: Context): PurchaseDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PurchaseDatabase::class.java,
                    "mon_panier.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5
                    )
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
