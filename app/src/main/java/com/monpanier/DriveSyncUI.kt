
package com.monpanier.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? {
    var current = this

    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }

    return current as? Activity
}

@Composable
fun DriveSyncSettingsCard(
    fg: Color,
    card: Color,
    ar: Boolean
) {
    val context = LocalContext.current

    val activity = remember(context) {
        context.findActivity()
    }

    val scope = rememberCoroutineScope()

    var connected by remember {
        mutableStateOf(
            DriveSyncManager.isConnected(context)
        )
    }

    var status by remember {
        mutableStateOf(
            DriveSyncManager.getStatus(context)
        )
    }

    var lastSync by remember {
        mutableStateOf(
            DriveSyncManager.getLastSync(context)
        )
    }

    var busy by remember {
        mutableStateOf(false)
    }

    fun refreshStatus() {
        connected = DriveSyncManager.isConnected(context)
        status = DriveSyncManager.getStatus(context)
        lastSync = DriveSyncManager.getLastSync(context)
    }

    fun updateStatus(message: String) {
        DriveSyncManager.setStatus(context, message)
        status = message
    }

    // -----------------------------------------------------
    // SYNCHRONISATION AVEC GOOGLE DRIVE
    // -----------------------------------------------------

    fun synchronizeWithToken(token: String) {

        scope.launch {

            busy = true

            updateStatus("Synchronisation en cours…")

            try {

                // Synchronisation et création automatique
                // du dossier et du fichier Google Drive.
                DriveSyncManager.synchronize(
                    context = context,
                    accessToken = token
                )

                // Connexion confirmée après synchronisation.
                DriveSyncManager.setConnected(
                    context,
                    true
                )

                // Activation de la synchronisation périodique.
                DriveSyncWorker.schedulePeriodic(context)

                updateStatus(
                    "Connecté — sauvegarde Google Drive à jour"
                )

            } catch (error: Exception) {

                DriveSyncManager.setConnected(
                    context,
                    false
                )

                updateStatus(
                    "Échec : ${error.localizedMessage ?: error.javaClass.simpleName}"
                )

            } finally {

                busy = false
                refreshStatus()
            }
        }
    }

    // -----------------------------------------------------
    // RETOUR DE L'AUTORISATION GOOGLE
    // -----------------------------------------------------

    val authorizationLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->

            if (result.resultCode == Activity.RESULT_OK) {

                val authorizationResult = runCatching {

                    Identity.getAuthorizationClient(context)
                        .getAuthorizationResultFromIntent(
                            result.data
                        )
                }

                authorizationResult.onSuccess { auth ->

                    val token = auth.accessToken

                    if (!token.isNullOrBlank()) {

                        synchronizeWithToken(token)

                    } else {

                        DriveSyncManager.setConnected(
                            context,
                            false
                        )

                        updateStatus(
                            "Google a répondu sans fournir de jeton d'accès."
                        )

                        refreshStatus()
                    }

                }.onFailure { error ->

                    DriveSyncManager.setConnected(
                        context,
                        false
                    )

                    updateStatus(
                        "Erreur de récupération du jeton : " +
                            (error.localizedMessage ?: "erreur inconnue")
                    )

                    refreshStatus()
                }

            } else {

                DriveSyncManager.setConnected(
                    context,
                    false
                )

                updateStatus(
                    "Autorisation interrompue. " +
                        "Code de résultat : ${result.resultCode}. " +
                        "Vérifie que tu as accepté l'accès Google Drive."
                )

                busy = false
                refreshStatus()
            }
        }

    // -----------------------------------------------------
    // DEMANDE DE CONNEXION GOOGLE
    // -----------------------------------------------------

    fun connectGoogle() {

        if (activity == null) {

            updateStatus(
                "Impossible d'ouvrir la connexion Google."
            )

            return
        }

        busy = true

        updateStatus(
            "Ouverture de l'autorisation Google…"
        )

        Identity.getAuthorizationClient(context)
            .authorize(
                DriveSyncManager.authorizationRequest()
            )
            .addOnSuccessListener { result ->

                if (result.hasResolution()) {

                    val pendingIntent = result.pendingIntent

                    if (pendingIntent != null) {

                        try {

                            val request =
                                IntentSenderRequest.Builder(
                                    pendingIntent.intentSender
                                ).build()

                            updateStatus(
                                "En attente de l'autorisation Google…"
                            )

                            authorizationLauncher.launch(request)

                        } catch (error: Exception) {

                            busy = false

                            updateStatus(
                                "Impossible d'ouvrir l'autorisation : " +
                                    (error.localizedMessage ?: "erreur inconnue")
                            )
                        }

                    } else {

                        busy = false

                        updateStatus(
                            "Google demande une autorisation, " +
                                "mais aucune fenêtre de validation n'est disponible."
                        )
                    }

                } else {

                    val token = result.accessToken

                    if (!token.isNullOrBlank()) {

                        synchronizeWithToken(token)

                    } else {

                        busy = false

                        updateStatus(
                            "Google n'a pas fourni de jeton d'accès."
                        )
                    }
                }
            }
            .addOnFailureListener { error ->

                busy = false

                DriveSyncManager.setConnected(
                    context,
                    false
                )

                updateStatus(
                    "Erreur Google : " +
                        (error.localizedMessage ?: "erreur inconnue")
                )

                refreshStatus()
            }
    }

    // -----------------------------------------------------
    // INTERFACE
    // -----------------------------------------------------

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = card
        )
    ) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            Row(
                verticalAlignment =
                    androidx.compose.ui.Alignment.CenterVertically
            ) {

                Icon(
                    if (connected) {
                        Icons.Default.CloudDone
                    } else {
                        Icons.Default.Cloud
                    },
                    contentDescription = null,
                    tint = Color(0xFF078A4B)
                )

                Spacer(
                    Modifier.width(8.dp)
                )

                Text(
                    if (ar) {
                        "المزامنة مع Google Drive"
                    } else {
                        "Synchronisation Google Drive"
                    },
                    color = fg,
                    fontSize = 15.sp
                )
            }

            Text(
                if (ar) {
                    "نسخة احتياطية للمشتريات والتذاكر والمتاجر والميزانيات."
                } else {
                    "Sauvegarde des achats, tickets, magasins et budgets."
                },
                color = fg.copy(alpha = 0.7f),
                fontSize = 12.sp
            )

            Text(
                if (connected) {
                    if (ar) "الحالة: متصل"
                    else "État : connecté"
                } else {
                    if (ar) "الحالة: غير متصل"
                    else "État : non connecté"
                },
                color = fg,
                fontSize = 12.sp
            )

            Text(
                status,
                color = fg.copy(alpha = 0.85f),
                fontSize = 12.sp
            )

            if (lastSync.isNotBlank()) {

                Text(
                    (if (ar) "آخر نسخة: " else "Dernière sauvegarde : ") +
                        lastSync,
                    color = fg.copy(alpha = 0.65f),
                    fontSize = 11.sp
                )
            }

            // Bouton de connexion.
            Button(
                onClick = {
                    connectGoogle()
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {

                if (busy) {

                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp
                    )

                    Spacer(Modifier.width(8.dp))
                }

                Text(
                    if (connected) {
                        if (ar) "إعادة الاتصال بـ Google"
                        else "Reconnecter Google"
                    } else {
                        if (ar) "الاتصال بـ Google Drive"
                        else "Connecter Google Drive"
                    },
                    fontSize = 13.sp
                )
            }

            // Bouton de synchronisation.
            OutlinedButton(
                onClick = {

                    if (connected) {

                        DriveSyncWorker.enqueue(context)

                        updateStatus(
                            "Synchronisation demandée…"
                        )

                    } else {

                        connectGoogle()
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {

                if (busy) {

                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp
                    )

                } else {

                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null
                    )
                }

                Spacer(
                    Modifier.width(8.dp)
                )

                Text(
                    if (ar) "مزامنة الآن"
                    else "Synchroniser maintenant",
                    fontSize = 13.sp
                )
            }
        }
    }
}
