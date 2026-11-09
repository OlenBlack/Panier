
package com.monpanier.app

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

@Composable
fun DriveSyncSettingsCard(
    fg: Color,
    card: Color,
    ar: Boolean
) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->

        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val authorization =
                    Identity.getAuthorizationClient(context)
                        .getAuthorizationResultFromIntent(
                            result.data
                        )

                if (!authorization.accessToken.isNullOrEmpty()) {
                    DriveSyncManager.enqueueSync(context)
                }

            } catch (_: Exception) {
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = card
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {

            Text(
                if (ar) "مزامنة Google Drive"
                else "Synchronisation Google Drive",
                color = fg,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                if (ar)
                    "احفظ مشترياتك ومتاجرك وميزانياتك تلقائياً على Google Drive."
                else
                    "Sauvegardez automatiquement vos achats, magasins et budgets sur Google Drive.",
                color = fg
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {

                    val request =
                        AuthorizationRequest.builder()
                            .setRequestedScopes(
                                listOf(
                                    Scope(
                                        "https://www.googleapis.com/auth/drive.file"
                                    )
                                )
                            )
                            .build()

                    val activity = context as? Activity

                    if (activity != null) {
                        Identity.getAuthorizationClient(activity)
                            .authorize(request)
                            .addOnSuccessListener { authorization ->

                                if (authorization.hasResolution()) {

                                    val pendingIntent =
                                        authorization.pendingIntent

                                    if (pendingIntent != null) {
                                        val intentRequest =
                                            IntentSenderRequest.Builder(
                                                pendingIntent.intentSender
                                            ).build()

                                        launcher.launch(intentRequest)
                                    }

                                } else {
                                    DriveSyncManager.enqueueSync(context)
                                }
                            }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (ar) "الاتصال بـ Google Drive"
                    else "Connecter Google Drive"
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                if (ar)
                    "تتم المزامنة عند تعديل البيانات، وكذلك دورياً عند توفر الإنترنت."
                else
                    "La synchronisation se déclenche après les modifications et périodiquement lorsque Internet est disponible.",
                color = fg,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
