
package com.monpanier.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class DriveSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val token = getAccessToken() ?: return@withContext Result.retry()

            val db = PurchaseDatabase.get(applicationContext)
            val dao = db.purchaseDao()

            val purchases = dao.getAll()
            val stores = dao.getAllStores()
            val budgets = dao.getAllBudgets()

            val drive = DriveRepository(token)

            val folderId = drive.getOrCreateFolder()
            val backupId = drive.findBackup(folderId)

            val localIsEmpty =
                purchases.isEmpty() &&
                stores.isEmpty() &&
                budgets.isEmpty()

            if (localIsEmpty && backupId != null) {
                val json = drive.downloadBackup(backupId)

                db.withTransaction {
                    dao.clearAll()
                    dao.clearStores()
                    dao.clearBudgets()

                    dao.restorePurchases(
                        BackupJson.readPurchases(json)
                    )
                    dao.restoreStores(
                        BackupJson.readStores(json)
                    )
                    dao.restoreBudgets(
                        BackupJson.readBudgets(json)
                    )
                }
            } else {
                val json = BackupJson.create(
                    purchases,
                    stores,
                    budgets
                )

                if (backupId == null) {
                    drive.createBackup(folderId, json)
                } else {
                    drive.updateBackup(backupId, json)
                }
            }

            Result.success()

        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun getAccessToken(): String? {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope(
                        "https://www.googleapis.com/auth/drive.file"
                    )
                )
            )
            .build()

        val result = Tasks.await(
            Identity.getAuthorizationClient(applicationContext)
                .authorize(request)
        )

        if (result.hasResolution()) {
            return null
        }

        return result.accessToken
    }
}

private object BackupJson {

    fun create(
        purchases: List<PurchaseEntity>,
        stores: List<StoreEntity>,
        budgets: List<BudgetEntity>
    ): String {

        val root = JSONObject()

        root.put("formatVersion", 1)
        root.put("savedAt", System.currentTimeMillis())

        val purchaseArray = JSONArray()

        purchases.forEach {
            purchaseArray.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("receiptDate", it.receiptDate)
                    put("store", it.store)
                    put("product", it.product)
                    put("quantity", it.quantity)
                    put("unitPrice", it.unitPrice)
                    put("total", it.total)
                    put("currency", it.currency)
                    put("ticketNumber", it.ticketNumber)
                    put("receiptId", it.receiptId)
                }
            )
        }

        val storeArray = JSONArray()

        stores.forEach {
            storeArray.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                }
            )
        }

        val budgetArray = JSONArray()

        budgets.forEach {
            budgetArray.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("month", it.month)
                    put("amount", it.amount)
                }
            )
        }

        root.put("purchases", purchaseArray)
        root.put("stores", storeArray)
        root.put("budgets", budgetArray)

        return root.toString(2)
    }

    fun readPurchases(json: String): List<PurchaseEntity> {
        val array = JSONObject(json).optJSONArray("purchases")
            ?: JSONArray()

        return List(array.length()) { i ->
            val item = array.getJSONObject(i)

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
        }
    }

    fun readStores(json: String): List<StoreEntity> {
        val array = JSONObject(json).optJSONArray("stores")
            ?: JSONArray()

        return List(array.length()) { i ->
            val item = array.getJSONObject(i)

            StoreEntity(
                id = item.getLong("id"),
                name = item.getString("name")
            )
        }
    }

    fun readBudgets(json: String): List<BudgetEntity> {
        val array = JSONObject(json).optJSONArray("budgets")
            ?: JSONArray()

        return List(array.length()) { i ->
            val item = array.getJSONObject(i)

            BudgetEntity(
                id = item.getLong("id"),
                month = item.getString("month"),
                amount = item.getDouble("amount")
            )
        }
    }
}

private class DriveRepository(
    private val token: String
) {

    companion object {
        private const val BASE =
            "https://www.googleapis.com/drive/v3"

        private const val UPLOAD =
            "https://www.googleapis.com/upload/drive/v3"

        private const val FOLDER_NAME = "Mon Panier"
        private const val BACKUP_NAME = "MonPanier_Backup.json"

        private const val FOLDER_MIME =
            "application/vnd.google-apps.folder"
    }

    private fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String = "application/json"
    ): String {

        val connection =
            URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.setRequestProperty(
                "Authorization",
                "Bearer $token"
            )
            connection.setRequestProperty(
                "Accept",
                "application/json"
            )
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    contentType
                )

                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val code = connection.responseCode

            val stream =
                if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val response = stream?.bufferedReader()?.use {
                it.readText()
            } ?: ""

            if (code !in 200..299) {
                throw Exception(
                    "Google Drive HTTP $code: $response"
                )
            }

            return response

        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    fun getOrCreateFolder(): String {

        val query = encode(
            "name='$FOLDER_NAME' and " +
            "mimeType='$FOLDER_MIME' and trashed=false"
        )

        val response = request(
            "$BASE/files?q=$query&fields=files(id,name)"
        )

        val files = JSONObject(response)
            .optJSONArray("files") ?: JSONArray()

        if (files.length() > 0) {
            return files.getJSONObject(0).getString("id")
        }

        val metadata = JSONObject().apply {
            put("name", FOLDER_NAME)
            put("mimeType", FOLDER_MIME)
        }

        val created = request(
            "$BASE/files",
            "POST",
            metadata.toString()
        )

        return JSONObject(created).getString("id")
    }

    fun findBackup(folderId: String): String? {

        val query = encode(
            "name='$BACKUP_NAME' and " +
            "'$folderId' in parents and trashed=false"
        )

        val response = request(
            "$BASE/files?q=$query&fields=files(id,name)"
        )

        val files = JSONObject(response)
            .optJSONArray("files") ?: JSONArray()

        return if (files.length() > 0) {
            files.getJSONObject(0).getString("id")
        } else {
            null
        }
    }

    fun downloadBackup(fileId: String): String {
        return request(
            "$BASE/files/$fileId?alt=media"
        )
    }

    fun createBackup(
        folderId: String,
        json: String
    ) {
        val boundary = "MonPanierBoundary123"

        val metadata = JSONObject().apply {
            put("name", BACKUP_NAME)
            put("mimeType", "application/json")
            put(
                "parents",
                JSONArray().put(folderId)
            )
        }

        val body =
            "--$boundary\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
            metadata.toString() +
            "\r\n--$boundary\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
            json +
            "\r\n--$boundary--"

        request(
            "$UPLOAD/files?uploadType=multipart",
            "POST",
            body,
            "multipart/related; boundary=$boundary"
        )
    }

    fun updateBackup(
        fileId: String,
        json: String
    ) {
        request(
            "$UPLOAD/files/$fileId?uploadType=media",
            "PATCH",
            json,
            "application/json"
        )
    }
}
