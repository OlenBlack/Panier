
package com.monpanier.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun LocalBackupSettingsCard(
    fg: Color,
    card: Color,
    ar: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var message by remember {
        mutableStateOf("")
    }

    val lastBackup by LocalBackupManager.lastBackupUpdates.collectAsState(
        initial = LocalBackupManager.getLastBackup(context)
    )

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->

        if (uri != null) {
            try {
                LocalBackupManager.saveFolderPermission(
                    context,
                    uri
                )

                scope.launch {
                    try {
                        LocalBackupManager.backup(context)

                        message = if (ar) {
                            "تم حفظ النسخة الاحتياطية بنجاح"
                        } else {
                            "Sauvegarde effectuée avec succès."
                        }

                    } catch (e: Exception) {
                        message = e.message ?: "Erreur de sauvegarde."
                    }
                }

            } catch (e: Exception) {
                message = e.message ?: "Erreur d'accès au dossier."
            }
        }
    }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->

        if (uri != null) {
            scope.launch {
                try {
                    LocalBackupManager.restore(context, uri)

                    message = if (ar) {
                        "تم استرجاع البيانات بنجاح"
                    } else {
                        "Restauration effectuée avec succès."
                    }

                } catch (e: Exception) {
                    message = e.message ?: "Erreur de restauration."
                }
            }
        }
    }

    val lastBackupText = remember(lastBackup, ar) {
        if (lastBackup == 0L) {
            if (ar) {
                "لم يتم إنشاء نسخة بعد"
            } else {
                "Aucune sauvegarde effectuée."
            }
        } else {
            val date = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT
            ).format(Date(lastBackup.toString().toLongOrNull() ?: 0L))

            if (ar) {
                "آخر نسخة: $date"
            } else {
                "Dernière sauvegarde : $date"
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text(
                if (ar) "النسخ الاحتياطي المحلي"
                else "Sauvegarde locale",
                color = fg,
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                if (ar)
                    "احفظ مشترياتك ومتاجرك وميزانياتك على هاتفك."
                else
                    "Conservez vos achats, magasins et budgets dans un fichier sur votre téléphone.",
                color = fg
            )

            Text(
                lastBackupText,
                color = fg,
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = {
                    if (LocalBackupManager.hasBackupFolder(context)) {
                        scope.launch {
                            try {
                                LocalBackupManager.backup(context)

                                message = if (ar) {
                                    "تم حفظ النسخة الاحتياطية بنجاح"
                                } else {
                                    "Sauvegarde effectuée avec succès."
                                }

                            } catch (e: Exception) {
                                message = e.message ?: "Erreur de sauvegarde."
                            }
                        }
                    } else {
                        folderPicker.launch(null)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (ar) "حفظ نسخة احتياطية"
                    else "Sauvegarder maintenant"
                )
            }

            OutlinedButton(
                onClick = {
                    restorePicker.launch(
                        arrayOf(
                            "application/json",
                            "text/plain",
                            "*/*"
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (ar) "استعادة نسخة احتياطية"
                    else "Restaurer une sauvegarde"
                )
            }

            OutlinedButton(
                onClick = {
                    folderPicker.launch(null)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (ar) "اختيار مجلد النسخ"
                    else "Choisir le dossier de sauvegarde"
                )
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    color = fg,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
