package com.monpanier.app

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DriveAuthorizationRequiredException :
    Exception("Une autorisation Google est nécessaire.")

object DriveSyncManager {

    private const val PREFS = "drive_sync"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_STATUS = "status"
    private const val KEY_LAST_SYNC = "last_sync"

    private const val FOLDER_NAME = "Mon Panier"
    private const val BACKUP_NAME = "MonPanier_Backup.json"

    private const val DRIVE_SCOPE =
        "https://www.googleapis.com/auth/drive.file"

    private const val FOLDER_MIME =
        "application/vnd.google-apps.folder"

    private const val JSON_MIME = "application/json"

    private const val BASE_URL =
        "https://www.googleapis.com/drive/v3"

    private const val UPLOAD_URL =
        "https://www.googleapis.com/upload/drive/v3"

    fun authorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(Scope(DRIVE_SCOPE))
            )
            .build()
    }

    fun setConnected(context: Context, connected: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONNECTED, connected)
            .apply()
    }

    fun isConnected(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONNECTED, false)
    }

    fun getStatus(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "Non connecté") ?: "Non connecté"
    }

    fun getLastSync(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SYNC, "") ?: ""
    }

    fun setStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, status)
            .apply()
    }

    fun enqueueSync(context: Context) {
        if (!isConnected(context)) return
        DriveSyncWorker.enqueue(context)
    }

    suspend fun getSilentAccessToken(context: Context): String {
        val client = Identity.getAuthorizationClient(context)

        val result = suspendCancellableCoroutine<AuthorizationResult> {
            continuation ->

            client.authorize(authorizationRequest())
                .addOnSuccessListener { authorizationResult ->
                    if (continuation.isActive) {
                        continuation.resume(authorizationResult)
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
        }

        if (result.hasResolution()) {
            throw DriveAuthorizationRequiredException()
        }

        return result.accessToken
            ?: throw IOException("Google n'a pas fourni de jeton d'accès.")
    }

    suspend fun synchronize(
        context: Context,
        accessToken: String
    ) = withContext(Dispatchers.IO) {

        setStatus(context, "Synchronisation en cours…")

        val database = PurchaseDatabase.get(context)
        val dao = database.purchaseDao()

        val purchases = dao.getAll()
        val stores = dao.getAllStores()
        val budgets = dao.getAllBudgets()

        val localIsEmpty =
            purchases.isEmpty() &&
            stores.isEmpty() &&
            budgets.isEmpty()

        val folderId = findOrCreateFolder(accessToken)
        val backupId = findBackupFile(accessToken, folderId)

        if (localIsEmpty && backupId != null) {
            val remoteBytes = downloadFile(accessToken, backupId)
            val backup = JSONObject(
                String(remoteBytes, Charsets.UTF_8)
            )

            val remotePurchases =
                readPurchases(backup.optJSONArray("purchases"))

            val remoteStores =
                readStores(backup.optJSONArray("stores"))

            val remoteBudgets =
                readBudgets(backup.optJSONArray("budgets"))

            database.withTransaction {
                dao.clearAll()
                dao.clearStores()
                dao.clearBudgets()

                if (remotePurchases.isNotEmpty()) {
                    dao.restorePurchases(remotePurchases)
                }

                if (remoteStores.isNotEmpty()) {
                    dao.restoreStores(remoteStores)
                }

                if (remoteBudgets.isNotEmpty()) {
                    dao.restoreBudgets(remoteBudgets)
                }
            }

            setStatus(context, "Sauvegarde restaurée depuis Google Drive")
        } else if (localIsEmpty && backupId == null) {
            setStatus(context, "Connecté — aucune donnée à sauvegarder")
        } else {
            val backup = createBackupJson(
                purchases,
                stores,
                budgets
            )

            uploadBackup(
                accessToken = accessToken,
                folderId = folderId,
                fileId = backupId,
                content = backup.toString()
            )

            setStatus(context, "Sauvegarde Google Drive à jour")
        }

        val date = java.text.SimpleDateFormat(
            "dd/MM/yyyy à HH:mm",
            java.util.Locale.getDefault()
        ).format(java.util.Date())

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SYNC, date)
            .apply()
    }

    private fun createBackupJson(
        purchases: List<PurchaseEntity>,
        stores: List<StoreEntity>,
        budgets: List<BudgetEntity>
    ): JSONObject {

        val purchaseArray = JSONArray()

        purchases.forEach { p ->
            purchaseArray.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("receiptDate", p.receiptDate)
                    put("store", p.store)
                    put("product", p.product)
                    put("quantity", p.quantity)
                    put("unitPrice", p.unitPrice)
                    put("total", p.total)
                    put("currency", p.currency)
                    put("ticketNumber", p.ticketNumber)
                    put("receiptId", p.receiptId)
                }
            )
        }

        val storeArray = JSONArray()

        stores.forEach { s ->
            storeArray.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                }
            )
        }

        val budgetArray = JSONArray()

        budgets.forEach { b ->
            budgetArray.put(
                JSONObject().apply {
                    put("id", b.id)
                    put("month", b.month)
                    put("amount", b.amount)
                }
            )
        }

        return JSONObject().apply {
            put("formatVersion", 1)
            put("exportedAt", System.currentTimeMillis())
            put("purchases", purchaseArray)
            put("stores", storeArray)
            put("budgets", budgetArray)
        }
    }

    private fun readPurchases(array: JSONArray?): List<PurchaseEntity> {
        if (array == null) return emptyList()

        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue

                add(
                    PurchaseEntity(
                        id = item.optLong("id"),
                        receiptDate = item.optString("receiptDate"),
                        store = item.optString("store"),
                        product = item.optString("product"),
                        quantity = item.optDouble("quantity"),
                        unitPrice = item.optDouble("unitPrice"),
                        total = item.optDouble("total"),
                        currency = item.optString("currency"),
                        ticketNumber = item.optString("ticketNumber"),
                        receiptId = item.optString("receiptId")
                    )
                )
            }
        }
    }

    private fun readStores(array: JSONArray?): List<StoreEntity> {
        if (array == null) return emptyList()

        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue

                add(
                    StoreEntity(
                        id = item.optLong("id"),
                        name = item.optString("name")
                    )
                )
            }
        }
    }

    private fun readBudgets(array: JSONArray?): List<BudgetEntity> {
        if (array == null) return emptyList()

        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue

                add(
                    BudgetEntity(
                        id = item.optLong("id"),
                        month = item.optString("month"),
                        amount = item.optDouble("amount")
                    )
                )
            }
        }
    }

    private fun findOrCreateFolder(token: String): String {
        val query = "name='$FOLDER_NAME' and " +
            "mimeType='$FOLDER_MIME' and trashed=false"

        val existing = listFiles(token, query)

        if (existing.length() > 0) {
            return existing.getJSONObject(0).getString("id")
        }

        val metadata = JSONObject().apply {
            put("name", FOLDER_NAME)
            put("mimeType", FOLDER_MIME)
        }

        val response = request(
            token = token,
            url = "$BASE_URL/files",
            method = "POST",
            body = metadata.toString().toByteArray(Charsets.UTF_8),
            contentType = JSON_MIME
        )

        return JSONObject(response).getString("id")
    }

    private fun findBackupFile(
        token: String,
        folderId: String
    ): String? {

        val query = "name='$BACKUP_NAME' and " +
            "'$folderId' in parents and trashed=false"

        val files = listFiles(token, query)

        if (files.length() == 0) return null

        return files.getJSONObject(0).getString("id")
    }

    private fun listFiles(
        token: String,
        query: String
    ): JSONArray {

        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val url = "$BASE_URL/files" +
            "?q=$encodedQuery" +
            "&spaces=drive" +
            "&pageSize=100" +
            "&fields=files(id,name,mimeType)"

        val response = request(
            token = token,
            url = url,
            method = "GET"
        )

        return JSONObject(response).optJSONArray("files")
            ?: JSONArray()
    }

    private fun downloadFile(
        token: String,
        fileId: String
    ): ByteArray {

        return requestBytes(
            token = token,
            url = "$BASE_URL/files/$fileId?alt=media",
            method = "GET"
        )
    }

    private fun uploadBackup(
        accessToken: String,
        folderId: String,
        fileId: String?,
        content: String
    ) {

        val bytes = content.toByteArray(Charsets.UTF_8)

        if (fileId != null) {
            requestBytes(
                token = accessToken,
                url = "$UPLOAD_URL/files/$fileId?uploadType=media",
                method = "PATCH",
                body = bytes,
                contentType = JSON_MIME
            )
            return
        }

        val boundary = "MonPanierBoundary${System.currentTimeMillis()}"

        val metadata = JSONObject().apply {
            put("name", BACKUP_NAME)
            put("mimeType", JSON_MIME)
            put("parents", JSONArray().put(folderId))
        }

        val output = java.io.ByteArrayOutputStream()

        output.write("--$boundary\r\n".toByteArray())
        output.write(
            "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                .toByteArray()
        )
        output.write(metadata.toString().toByteArray(Charsets.UTF_8))
        output.write("\r\n".toByteArray())

        output.write("--$boundary\r\n".toByteArray())
        output.write("Content-Type: $JSON_MIME\r\n\r\n".toByteArray())
        output.write(bytes)
        output.write("\r\n--$boundary--\r\n".toByteArray())

        requestBytes(
            token = accessToken,
            url = "$UPLOAD_URL/files?uploadType=multipart",
            method = "POST",
            body = output.toByteArray(),
            contentType = "multipart/related; boundary=$boundary"
        )
    }

    private fun request(
        token: String,
        url: String,
        method: String,
        body: ByteArray? = null,
        contentType: String? = null
    ): String {
        return String(
            requestBytes(token, url, method, body, contentType),
            Charsets.UTF_8
        )
    }

    private fun requestBytes(
        token: String,
        url: String,
        method: String,
        body: ByteArray? = null,
        contentType: String? = null
    ): ByteArray {

        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )

                if (contentType != null) {
                    setRequestProperty("Content-Type", contentType)
                }

                if (body != null) {
                    doOutput = true
                }
            }

        try {
            if (body != null) {
                connection.outputStream.use {
                    it.write(body)
                }
            }

            val code = connection.responseCode

            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.use {
                it.readBytes()
            } ?: ByteArray(0)

            if (code !in 200..299) {
                throw IOException(
                    "Google Drive HTTP $code : " +
                        String(response, Charsets.UTF_8)
                )
            }

            return response
        } finally {
            connection.disconnect()
        }
    }
}
