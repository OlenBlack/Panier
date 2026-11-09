
package com.monpanier.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object DriveFileRepository {

    private const val FOLDER_NAME = "Mon Panier"
    private const val BACKUP_NAME = "MonPanier_Backup.json"

    private const val DRIVE_API =
        "https://www.googleapis.com/drive/v3/files"

    private const val DRIVE_UPLOAD =
        "https://www.googleapis.com/upload/drive/v3/files"

    private fun request(
        url: String,
        token: String,
        method: String,
        body: ByteArray? = null,
        contentType: String? = null
    ): ByteArray {

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

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    contentType ?: "application/json"
                )

                connection.outputStream.use {
                    it.write(body)
                }
            }

            val status = connection.responseCode

            val stream =
                if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val response = stream?.use {
                it.readBytes()
            } ?: ByteArray(0)

            if (status !in 200..299) {
                throw Exception(
                    "Google Drive erreur HTTP $status : " +
                        response.decodeToString()
                )
            }

            return response

        } finally {
            connection.disconnect()
        }
    }

    private fun escapeQuery(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
    }

    suspend fun findOrCreateFolder(
        token: String
    ): String = withContext(Dispatchers.IO) {

        val query =
            "name='${escapeQuery(FOLDER_NAME)}' " +
            "and mimeType='application/vnd.google-apps.folder' " +
            "and trashed=false"

        val url =
            "$DRIVE_API?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            "&fields=files(id,name)"

        val result = JSONObject(
            request(url, token, "GET").decodeToString()
        )

        val files = result.optJSONArray("files") ?: JSONArray()

        if (files.length() > 0) {
            return@withContext files
                .getJSONObject(0)
                .getString("id")
        }

        val metadata = JSONObject().apply {
            put("name", FOLDER_NAME)
            put(
                "mimeType",
                "application/vnd.google-apps.folder"
            )
        }

        val created = JSONObject(
            request(
                DRIVE_API,
                token,
                "POST",
                metadata.toString().toByteArray(),
                "application/json"
            ).decodeToString()
        )

        created.getString("id")
    }

    private suspend fun findBackupFile(
        token: String,
        folderId: String
    ): String? = withContext(Dispatchers.IO) {

        val query =
            "name='${escapeQuery(BACKUP_NAME)}' " +
            "and '$folderId' in parents " +
            "and trashed=false"

        val url =
            "$DRIVE_API?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            "&fields=files(id,name,modifiedTime,headRevisionId)"

        val result = JSONObject(
            request(url, token, "GET").decodeToString()
        )

        val files = result.optJSONArray("files") ?: JSONArray()

        if (files.length() == 0) {
            null
        } else {
            files.getJSONObject(0).getString("id")
        }
    }

    suspend fun synchronizeBackup(
        token: String,
        jsonContent: String
    ): String = withContext(Dispatchers.IO) {

        // Recherche ou création automatique du dossier.
        val folderId = findOrCreateFolder(token)

        // Recherche du fichier existant.
        val existingFileId =
            findBackupFile(token, folderId)

        val content = jsonContent.toByteArray(Charsets.UTF_8)

        if (existingFileId != null) {

            // Le fichier existe :
            // on conserve son ID et on actualise son contenu.
            val updateUrl =
                "$DRIVE_UPLOAD/$existingFileId?uploadType=media"

            request(
                updateUrl,
                token,
                "PATCH",
                content,
                "application/json; charset=UTF-8"
            )

            existingFileId

        } else {

            // Le fichier n'existe pas :
            // création d'un nouveau fichier.
            val boundary =
                "MonPanier_${UUID.randomUUID()}"

            val metadata = JSONObject().apply {
                put("name", BACKUP_NAME)
                put("mimeType", "application/json")
                put(
                    "parents",
                    JSONArray().put(folderId)
                )
            }

            val output = ByteArrayOutputStream()

            output.write(
                "--$boundary\r\n".toByteArray()
            )
            output.write(
                "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                    .toByteArray()
            )
            output.write(
                metadata.toString().toByteArray()
            )
            output.write("\r\n".toByteArray())

            output.write(
                "--$boundary\r\n".toByteArray()
            )
            output.write(
                "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                    .toByteArray()
            )
            output.write(content)
            output.write("\r\n".toByteArray())

            output.write(
                "--$boundary--\r\n".toByteArray()
            )

            val created = JSONObject(
                request(
                    "$DRIVE_UPLOAD?uploadType=multipart",
                    token,
                    "POST",
                    output.toByteArray(),
                    "multipart/related; boundary=$boundary"
                ).decodeToString()
            )

            created.getString("id")
        }
    }
}
