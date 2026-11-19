package app.filo.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.filo.data.FileActions
import app.filo.vault.BackupFile
import app.filo.vault.BackupSource
import app.filo.vault.OpenedBackup
import app.filo.vault.PasswordStrength
import app.filo.vault.WrongPasswordException
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A Filo backup opens the hidden folder (after unlocking, or setting it up on a new phone) to import it. */
fun openBackup(nav: NavController, actions: FileActions, file: File) {
    actions.pendingImport.value = BackupSource(file.name, file.length()) { file.inputStream() }
    if (!nav.popBackStack(Routes.VAULT, inclusive = false)) nav.navigate(Routes.VAULT)
}

/** A backup picked with Android's file picker (from a USB stick, for example). */
internal fun backupSourceOf(context: Context, uri: Uri): BackupSource {
    var name = uri.lastPathSegment ?: "backup"
    var size = -1L
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    return BackupSource(name, size) { context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open $name") }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    visible: Boolean,
    onToggle: (() -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = onToggle?.let { toggle ->
            {
                IconButton(onClick = toggle) {
                    Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Hide password" else "Show password")
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StrengthMeter(strength: PasswordStrength) {
    val color = if (strength.acceptable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { (strength.ordinal + 1f) / PasswordStrength.entries.size },
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Strength: ${strength.label}" + if (strength == PasswordStrength.TOO_SHORT) " (at least ${BackupFile.MIN_LENGTH} characters)" else "",
            color = color,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Choose the password for a backup. Weak ones are refused; it has to be typed twice. */
@Composable
fun ExportBackupDialog(files: Int, size: Long, onDismiss: () -> Unit, onExport: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val strength = PasswordStrength.of(password)
    val mismatch = again.isNotEmpty() && again != password

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Back up hidden folder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "One file with your ${if (files == 1) "1 hidden file" else "$files hidden files"} (${formatSize(size)}), Filo settings, " +
                        "favorites and saved servers, locked with a password. It's saved in Download. " +
                        "Copy it to your other phone and open it there in Filo.",
                )
                PasswordField("Password", password, { password = it }, visible, onToggle = { visible = !visible })
                if (password.isNotEmpty()) StrengthMeter(strength)
                PasswordField("Same password again", again, { again = it }, visible, isError = mismatch)
                if (mismatch) Text("The passwords don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Write the password down. Without it nobody can open the backup, not even you. " +
                        "Your PIN and fingerprint stay on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = strength.acceptable && again == password,
                onClick = {
                    onDismiss()
                    onExport(password)
                },
            ) { Text("Back up") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Enter a backup's password. It's checked here, so a typo can simply be fixed. */
@Composable
fun ImportBackupDialog(source: BackupSource, actions: FileActions, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Import backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(source.name + if (source.size > 0) " · ${formatSize(source.size)}" else "", fontWeight = FontWeight.Medium)
                Text(
                    "Its hidden files are added to this hidden folder; files already here are skipped. " +
                        "Filo's settings, favorites and saved servers are taken from the backup.",
                )
                PasswordField("Backup password", password, {
                    password = it
                    error = null
                }, visible, onToggle = { visible = !visible }, isError = error != null, enabled = !busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Checking the password…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && password.isNotEmpty(),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val opened = withContext(Dispatchers.IO) { OpenedBackup.open(source, password) }
                            onDismiss()
                            actions.importBackup(opened)
                        } catch (_: WrongPasswordException) {
                            error = "Wrong password"
                        } catch (e: Exception) {
                            error = e.message ?: "Couldn't open the backup"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
