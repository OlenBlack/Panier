
package com.monpanier.app

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.launch

@Composable
fun DriveSyncSettingsCard(
    fg: Color,
    card: Color,
    ar: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember {
        mutableStateOf(DriveSyncManager.getStatus(context))
    }

    var lastSync by remember {
        mutableStateOf(DriveSyncManager.getLastSync(context))
    }

    var loading by remember {
        mutableStateOf(false)
    }

    fun synchronizeWithToken(token: String) {
        scope.launch {
            try {
                loading = true

                DriveSyncManager.setConnected(context, true)
                
                DriveSyncWorker.schedulePeriodic(context)

                DriveSyncManager.synchronize(
                    context = context,
                    accessToken = token
                )

                status = DriveSyncManager.getStatus(context)
                lastSync = DriveSyncManager.getLastSync(context)

            } catch (e: Exception) {
                status = DriveSyncManager.getStatus(context)
                    .ifBlank { "Erreur : ${e.message}" }
            } finally {
                loading = false
            }
        }
    }

    val authorizationLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->

            if (result.resultCode != Activity.RESULT_OK) {
                loading = false
                status = "Autorisation Google annulée."
                return@rememberLauncherForActivityResult
            }

            try {
                val data = result.data

                if (data == null) {
                    loading = false
                    status = "Résultat Google manquant."
                    return@rememberLauncherForActivityResult
                }

                val authorizationResult =
                    Identity.getAuthorizationClient(context)
                        .getAuthorizationResultFromIntent(data)

                val token = authorizationResult.accessToken

                if (token.isNullOrBlank()) {
                    loading = false
                    status = "Google n'a pas fourni de jeton."
                } else {
                    synchronizeWithToken(token)
                }

            } catch (e: Exception) {
                loading = false
                status = "Erreur d'autorisation : ${e.message}"
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
                .padding(16.dp),
            horizontalAlignment = if (ar) {
                Alignment.End
            } else {
                Alignment.Start
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text(
                text = "Synchronisation Google Drive",
                color = fg
            )

            Text(
                text = "État : $status",
                color = fg,
                textAlign = if (ar) {
                    TextAlign.End
                } else {
                    TextAlign.Start
                }
            )

            if (lastSync.isNotBlank()) {
                Text(
                    text = "Dernière synchronisation : $lastSync",
                    color = fg
                )
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
                onClick = {

                    loading = true
                    status = "Connexion à Google..."

                    try {
                        val client =
                            Identity.getAuthorizationClient(context)

                        client.authorize(
                            DriveSyncManager.authorizationRequest()
                        )
                            .addOnSuccessListener { authorizationResult ->

                                if (authorizationResult.hasResolution()) {

                                    val pendingIntent =
                                        authorizationResult.pendingIntent

                                    if (pendingIntent == null) {
                                        loading = false
                                        status = "Autorisation Google indisponible."
                                    } else {
                                        try {
                                            val request =
                                                IntentSenderRequest.Builder(
                                                    pendingIntent.intentSender
                                                ).build()

                                            authorizationLauncher.launch(request)

                                        } catch (e: Exception) {
                                            loading = false
                                            status = "Impossible d'ouvrir Google : ${e.message}"
                                        }
                                    }

                                } else {

                                    val token =
                                        authorizationResult.accessToken

                                    if (token.isNullOrBlank()) {
                                        loading = false
                                        status = "Jeton Google indisponible."
                                    } else {
                                        synchronizeWithToken(token)
                                    }
                                }
                            }
                            .addOnFailureListener { error ->
                                loading = false
                                status = "Erreur Google : ${error.message}"
                            }

                    } catch (e: Exception) {
                        loading = false
                        status = "Erreur : ${e.message}"
                    }
                }
            ) {
                Text("Connecter / Synchroniser Google Drive")
            }

            if (loading) {
                CircularProgressIndicator()
            }
        }
    }
}
