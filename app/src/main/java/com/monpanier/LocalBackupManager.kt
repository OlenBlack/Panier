
package com.monpanier.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

object LocalBackupManager {

    private const val PREFS = "mon_panier_local_backup"
    private const val FOLDER_URI = "backup_folder_uri"
    private const val LAST_BACKUP = "last_backup_timestamp"

    const val FILE_NAME = "MonPanier_Backup.json"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasBackupFolder(context: Context): Boolean {
        return !prefs(context).getString(FOLDER_URI, null).isNullOrEmpty()
    }

    fun saveFolderPermission(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        prefs(context).edit()
            .putString(FOLDER_URI, uri.toString())
            .apply()
    }

    fun getLastBackup(context: Context): Long {
        return prefs(context).getLong(LAST_BACKUP, 0L)
    }

    suspend fun backup(context: Context) {
        val folderString = prefs(context).getString(FOLDER_URI, null)
            ?: throw IllegalStateException(
                "Aucun dossier de sauvegarde sélectionné."
            )

        val folderUri = Uri.parse(folderString)

        val database = PurchaseDatabase.get(context)
        val dao = database.purchaseDao()

        val purchases = dao.getAll()
        val stores = dao.getAllStores()
        val budgets = dao.getAllBudgets()

        val json = JSONObject().apply {
            put("formatVersion", 1)
            put("savedAt", System.currentTimeMillis())

            put("purchases", JSONArray().apply {
                purchases.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("receiptDate", item.receiptDate)
                        put("store", item.store)
                        put("product", item.product)
                        put("quantity", item.quantity)
                        put("unitPrice", item.unitPrice)
                        put("total", item.total)
                        put("currency", item.currency)
                        put("ticketNumber", item.ticketNumber)
                        put("receiptId", item.receiptId)
                    })
                }
            })

            put("stores", JSONArray().apply {
                stores.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                    })
                }
            })

            put("budgets", JSONArray().apply {
                budgets.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("month", item.month)
                        put("amount", item.amount)
                    })
                }
            })
        }

        val resolver = context.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(folderUri)

        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri,
                treeId
            )

        var backupUri: Uri? = null

        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->

            val idIndex = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )

            val nameIndex = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )

            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == FILE_NAME) {
                    backupUri =
                        DocumentsContract.buildDocumentUriUsingTree(
                            folderUri,
                            cursor.getString(idIndex)
                        )
                    break
                }
            }
        }

        if (backupUri == null) {
            val parentUri =
                DocumentsContract.buildDocumentUriUsingTree(
                    folderUri,
                    treeId
                )

            backupUri = DocumentsContract.createDocument(
                resolver,
                parentUri,
                "application/json",
                FILE_NAME
            )
        }

        val destination = backupUri
            ?: throw IllegalStateException(
                "Impossible de créer le fichier de sauvegarde."
            )

        resolver.openOutputStream(destination, "wt")?.bufferedWriter(
            Charsets.UTF_8
        )?.use { writer ->
            writer.write(json.toString(2))
        } ?: throw IllegalStateException(
            "Impossible d'écrire le fichier JSON."
        )

        prefs(context).edit()
            .putLong(LAST_BACKUP, System.currentTimeMillis())
            .apply()
    }

    suspend fun restore(context: Context, sourceUri: Uri) {
        val jsonText = context.contentResolver
            .openInputStream(sourceUri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: throw IllegalStateException(
                "Impossible de lire le fichier."
            )

        val root = JSONObject(jsonText)

        require(root.optInt("formatVersion", -1) == 1) {
            "Format de sauvegarde non reconnu."
        }

        val purchaseArray = root.getJSONArray("purchases")
        val storeArray = root.getJSONArray("stores")
        val budgetArray = root.getJSONArray("budgets")

        val purchases = mutableListOf<PurchaseEntity>()

        for (i in 0 until purchaseArray.length()) {
            val item = purchaseArray.getJSONObject(i)

            purchases.add(
                PurchaseEntity(
                    id = item.getLong("id"),
                    receiptDate = item.getString("receiptDate"),
                    store = item.getString("store"),
                    product = item.getString("product"),
                    quantity = item.getDouble("quantity"),
                    unitPrice = item.getDouble("unitPrice"),
                    total = item.getDouble("total"),
                    currency = item.getString("currency"),
                    ticketNumber = item.optString("ticketNumber", ""),
                    receiptId = item.getString("receiptId")
                )
            )
        }

        val stores = mutableListOf<StoreEntity>()

        for (i in 0 until storeArray.length()) {
            val item = storeArray.getJSONObject(i)

            stores.add(
                StoreEntity(
                    id = item.getLong("id"),
                    name = item.getString("name")
                )
            )
        }

        val budgets = mutableListOf<BudgetEntity>()

        for (i in 0 until budgetArray.length()) {
            val item = budgetArray.getJSONObject(i)

            budgets.add(
                BudgetEntity(
                    id = item.getLong("id"),
                    month = item.getString("month"),
                    amount = item.getDouble("amount")
                )
            )
        }

        val database = PurchaseDatabase.get(context)
        val dao = database.purchaseDao()

        // La restauration remplace les données locales.
        // La transaction évite une restauration partielle.
        database.withTransaction {
            dao.clearAll()
            dao.clearStores()
            dao.clearBudgets()

            if (purchases.isNotEmpty()) {
                dao.restorePurchases(purchases)
            }

            if (stores.isNotEmpty()) {
                dao.restoreStores(stores)
            }

            if (budgets.isNotEmpty()) {
                dao.restoreBudgets(budgets)
            }
        }
    }
}
