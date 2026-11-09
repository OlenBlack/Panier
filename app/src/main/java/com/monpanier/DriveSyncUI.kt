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
        mutableStateOf(DriveSyncManager.isConnected(context))
    }

    var status by remember {
        mutableStateOf(DriveSyncManager.getStatus(context))
    }

    var lastSync by remember {
        mutableStateOf(DriveSyncManager.getLastSync(context))
    }

    var busy by remember {
        mutableStateOf(false)
    }

    fun refreshStatus() {
        connected = DriveSyncManager.isConnected(context)
        status = DriveSyncManager.getStatus(context)
        lastSync = DriveSyncManager.getLastSync(context)
    }

    fun synchronizeWithToken(token: String) {
        scope.launch {
            busy = true
            status = "Synchronisation en cours…"

            runCatching {
                DriveSyncManager.synchronize(context, token)
            }.onSuccess {
                DriveSyncManager.setConnected(context, true)
                DriveSyncWorker.schedulePeriodic(context)
            }.onFailure {
                DriveSyncManager.setStatus(
                    context,
                    "Erreur : ${it.localizedMessage ?: "synchronisation impossible"}"
                )
            }

            busy = false
            refreshStatus()
        }
    }

    val authorizationLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->

            if (result.resultCode == Activity.RESULT_OK) {
                val authorizationResult = runCatching {
                    Identity.getAuthorizationClient(context)
                        .getAuthorizationResultFromIntent(result.data)
                }

                authorizationResult.onSuccess { auth ->
                    val token = auth.accessToken

                    if (!token.isNullOrBlank()) {
                        DriveSyncManager.setConnected(context, true)
                        synchronizeWithToken(token)
                    } else {
                        DriveSyncManager.setStatus(
                            context,
                            "Jeton Google indisponible"
                        )
                        refreshStatus()
                    }
                }.onFailure {
                    DriveSyncManager.setStatus(
                        context,
                        "Autorisation Google échouée"
                    )
                    refreshStatus()
                }
            } else {
                DriveSyncManager.setStatus(
                    context,
                    "Connexion Google annulée"
                )
                refreshStatus()
            }
        }

    fun connectGoogle() {
        if (activity == null) {
            status = "Impossible d'ouvrir la connexion Google"
            return
        }

        busy = true

        Identity.getAuthorizationClient(context)
            .authorize(DriveSyncManager.authorizationRequest())
            .addOnSuccessListener { result ->

                busy = false

                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent

                    if (pendingIntent != null) {
                        val request = IntentSenderRequest.Builder(
                            pendingIntent.intentSender
                        ).build()

                        authorizationLauncher.launch(request)
                    } else {
                        status = "Autorisation Google indisponible"
                    }
                } else {
                    val token = result.accessToken

                    if (!token.isNullOrBlank()) {
                        DriveSyncManager.setConnected(context, true)
                        synchronizeWithToken(token)
                    } else {
                        status = "Jeton Google indisponible"
                    }
                }
            }
            .addOnFailureListener { error ->
                busy = false
                status = "Erreur Google : ${error.localizedMessage}"
            }
    }

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
            Row {
                Icon(
                    if (connected) Icons.Default.CloudDone
                    else Icons.Default.Cloud,
                    contentDescription = null,
                    tint = Color(0xFF078A4B)
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    if (ar) "المزامنة مع Google Drive"
                    else "Synchronisation Google Drive",
                    color = fg,
                    fontSize = 15.sp
                )
            }

            Text(
                if (ar)
                    "نسخة احتياطية للمشتريات والتذاكر والمتاجر والميزانيات."
                else
                    "Sauvegarde des achats, tickets, magasins et budgets.",
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
                color = fg.copy(alpha = 0.8f),
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

            Button(
                onClick = {
                    connectGoogle()
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
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

            OutlinedButton(
                onClick = {
                    if (connected) {
                        DriveSyncWorker.enqueue(context)
                        status = "Synchronisation demandée…"
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
                    Icon(Icons.Default.Sync, null)
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    if (ar) "مزامنة الآن"
                    else "Synchroniser maintenant",
                    fontSize = 13.sp
                )
            }
        }
    }
}
