package app.filo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.filo.data.FileKind
import app.filo.data.Opener
import app.filo.vault.UnlockResult
import app.filo.vault.VaultItem
import app.filo.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.crypto.Cipher

// ---------------------------------------------------------------- helpers

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** No screenshots, screen recording or recent-apps preview while the hidden folder is visible. */
@Composable
fun SecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

fun canUseFingerprint(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

private fun authenticate(
    context: Context,
    title: String,
    cipher: Cipher,
    onSuccess: (Cipher) -> Unit,
    onError: (String?) -> Unit,
) {
    val activity = context.findActivity() as? FragmentActivity ?: return onError("Fingerprint isn't available here")
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onSuccess) ?: onError("Fingerprint didn't work. Use your PIN.")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onError(if (cancelled) null else errString.toString())
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setNegativeButtonText("Use PIN")
        .setAllowedAuthenticators(BIOMETRIC_STRONG)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}

/** Decodes a picture at roughly [target] pixels, upright according to its EXIF data. */
private fun decodeImage(bytes: ByteArray, target: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val rotation = try {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) {
        0f
    }
    val upright = if (rotation == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
    return upright.asImageBitmap()
}

// ---------------------------------------------------------------- PIN pad

@Composable
fun PinDots(filled: Int, error: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.semantics { contentDescription = "$filled of ${VaultManager.PIN_LENGTH} digits entered" },
    ) {
        repeat(VaultManager.PIN_LENGTH) { i ->
            val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .then(if (i < filled) Modifier.background(color) else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)),
            )
        }
    }
}

@Composable
fun PinPad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onFingerprint: (() -> Unit)? = null,
) {
    val keySize = 76.dp
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { digit -> DigitKey(digit, keySize, enabled) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (onFingerprint != null) {
                IconKey(Icons.Outlined.Fingerprint, "Unlock with fingerprint", keySize, enabled, onFingerprint)
            } else {
                Spacer(Modifier.size(keySize))
            }
            DigitKey('0', keySize, enabled) { onDigit('0') }
            IconKey(Icons.AutoMirrored.Outlined.Backspace, "Delete last digit", keySize, enabled, onBackspace)
        }
    }
}

@Composable
private fun DigitKey(digit: Char, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Text(digit.toString(), style = MaterialTheme.typography.headlineMedium) }
    }
}

@Composable
private fun IconKey(icon: ImageVector, label: String, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
private fun PinScreenLayout(title: String, message: String, error: Boolean, busy: Boolean, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(64.dp),
        )
        Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(28.dp))
        }
        content()
    }
}

// ---------------------------------------------------------------- Screens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(nav: NavController) {
    val graph = LocalGraph.current
    val setUp by graph.vault.setUp.collectAsStateWithLifecycle()
    val unlocked by graph.vault.unlocked.collectAsStateWithLifecycle()
    val pending by graph.actions.pendingVault.collectAsStateWithLifecycle()
    var justCreated by remember { mutableStateOf(false) }
    SecureWindow()
    // Leaving without unlocking cancels "move to hidden folder" for the waiting files.
    DisposableEffect(Unit) { onDispose { graph.actions.pendingVault.value = emptyList() } }
    if (setUp && unlocked) {
        VaultContent(nav, offerFingerprint = justCreated, onOfferDone = { justCreated = false })
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hidden folder") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (pending.isNotEmpty()) {
                Text(
                    "Unlock to hide ${itemCount(pending.size).replace("item", "file")}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            if (!setUp) VaultSetup(onCreated = { justCreated = true }) else VaultUnlock()
        }
    }
}

@Composable
private fun VaultSetup(onCreated: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val message = when {
        error -> "The PINs didn't match. Start again."
        first == null -> "Files you move here are encrypted and only visible inside Filo. " +
            "Choose a 6-digit PIN. If you forget it, the files can't be recovered."
        else -> "Enter the same PIN again to confirm it."
    }

    fun complete(entered: String) {
        val firstPin = first
        if (firstPin == null) {
            first = entered
            pin = ""
            return
        }
        if (firstPin != entered) {
            error = true
            first = null
            pin = ""
            return
        }
        busy = true
        scope.launch {
            try {
                if (canUseFingerprint(context)) onCreated()
                withContext(Dispatchers.Default) { graph.vault.setup(entered) }
            } catch (e: Exception) {
                graph.actions.notify(e.message ?: "Couldn't create the hidden folder")
            } finally {
                busy = false
                pin = ""
            }
        }
    }

    PinScreenLayout(if (first == null) "Create a PIN" else "Confirm your PIN", message, error, busy) {
        PinDots(pin.length, error)
        Spacer(Modifier.height(28.dp))
        PinPad(
            enabled = !busy,
            onDigit = { d ->
                error = false
                if (pin.length < VaultManager.PIN_LENGTH) pin += d
                if (pin.length == VaultManager.PIN_LENGTH) complete(pin)
            },
            onBackspace = { pin = pin.dropLast(1) },
        )
    }
}

@Composable
private fun FingerprintOfferDialog(onEnabled: () -> Unit, onDone: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDone,
        icon = { Icon(Icons.Outlined.Fingerprint, null) },
        title = { Text("Unlock with fingerprint too?") },
        text = { Text("You can always use your PIN instead.") },
        confirmButton = {
            Button(onClick = {
                onDone()
                enableFingerprint(context, graph) { if (it) onEnabled() }
            }) { Text("Use fingerprint") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Not now") } },
    )
}

private fun enableFingerprint(context: Context, graph: app.filo.Graph, onResult: (Boolean) -> Unit) {
    val cipher = try {
        graph.biometricKey.newEncryptCipher()
    } catch (e: Exception) {
        graph.actions.notify("Fingerprint can't be used: ${e.message ?: "set up a screen lock first"}")
        onResult(false)
        return
    }
    authenticate(
        context,
        "Turn on fingerprint unlock",
        cipher,
        onSuccess = {
            try {
                graph.vault.enableBiometric(it)
                graph.actions.notify("Fingerprint unlock is on")
                onResult(true)
            } catch (e: Exception) {
                graph.actions.notify(e.message ?: "Couldn't turn on fingerprint unlock")
                onResult(false)
            }
        },
        onError = {
            it?.let(graph.actions::notify)
            onResult(false)
        },
    )
}

@Composable
private fun VaultUnlock() {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableLongStateOf(graph.vault.lockedUntil()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var forgot by remember { mutableStateOf(false) }
    val fingerprint = graph.vault.biometricEnabled && canUseFingerprint(context)

    LaunchedEffect(lockedUntil) {
        while (lockedUntil > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1000)
        }
        now = System.currentTimeMillis()
    }
    val lockedOut = lockedUntil > now

    fun tryFingerprint() {
        val iv = graph.vault.biometricIv() ?: return
        val cipher = try {
            graph.biometricKey.decryptCipher(iv)
        } catch (_: KeyPermanentlyInvalidatedException) {
            graph.vault.disableBiometric()
            message = "Your fingerprints changed, so fingerprint unlock was turned off. Use your PIN, then turn it on again."
            return
        } catch (e: Exception) {
            message = "Fingerprint isn't available right now. Use your PIN."
            return
        }
        authenticate(
            context,
            "Unlock hidden folder",
            cipher,
            onSuccess = {
                val ok = try {
                    graph.vault.unlockWithBiometric(it)
                } catch (_: Exception) {
                    false
                }
                if (!ok) message = "Fingerprint didn't work. Use your PIN."
            },
            onError = { if (it != null) message = it },
        )
    }

    LaunchedEffect(Unit) { if (fingerprint && !lockedOut) tryFingerprint() }

    fun submit(entered: String) {
        busy = true
        scope.launch {
            val result = try {
                withContext(Dispatchers.Default) { graph.vault.unlockWithPin(entered) }
            } catch (e: Exception) {
                busy = false
                pin = ""
                message = "Couldn't unlock: ${e.message ?: "unknown error"}"
                return@launch
            }
            busy = false
            pin = ""
            when (result) {
                UnlockResult.Success -> message = null
                is UnlockResult.WrongPin -> message = "Wrong PIN. ${result.attemptsBeforeLockout} more ${if (result.attemptsBeforeLockout == 1) "try" else "tries"} before a short wait."
                is UnlockResult.LockedOut -> {
                    lockedUntil = result.untilMillis
                    message = null
                }
            }
        }
    }

    val text = when {
        lockedOut -> {
            val seconds = ((lockedUntil - now) / 1000).coerceAtLeast(1)
            "Too many wrong PINs. Try again in ${seconds / 60}:${"%02d".format(seconds % 60)}."
        }
        message != null -> message!!
        else -> "Enter your 6-digit PIN."
    }

    PinScreenLayout("Hidden folder is locked", text, error = lockedOut || message != null, busy = busy) {
        PinDots(pin.length)
        Spacer(Modifier.height(28.dp))
        PinPad(
            enabled = !busy && !lockedOut,
            onDigit = { d ->
                if (pin.length < VaultManager.PIN_LENGTH) pin += d
                if (pin.length == VaultManager.PIN_LENGTH) submit(pin)
            },
            onBackspace = { pin = pin.dropLast(1) },
            onFingerprint = if (fingerprint) ({ tryFingerprint() }) else null,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { forgot = true }) { Text("Forgot PIN?") }
    }

    if (forgot) EraseDialog { forgot = false }
}

@Composable
private fun EraseDialog(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forgot your PIN?") },
        text = {
            Column {
                Text(
                    "Without the PIN nobody can open these files, not even Filo. " +
                        "You can erase the hidden folder and start over. Every file inside it will be gone for good.",
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickableRow { understood = !understood }) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("I understand the files will be deleted")
                }
            }
        },
        confirmButton = {
            Button(enabled = understood, onClick = {
                onDismiss()
                graph.biometricKey.delete()
                graph.vault.erase()
                graph.actions.notify("Hidden folder erased")
            }) { Text("Erase hidden folder") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun VaultContent(nav: NavController, offerFingerprint: Boolean, onOfferDone: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by graph.vault.items.collectAsStateWithLifecycle()
    val pending by graph.actions.pendingVault.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var menu by remember { mutableStateOf(false) }
    var changePin by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var fingerprintOn by remember { mutableStateOf(graph.vault.biometricEnabled) }

    LaunchedEffect(pending) {
        if (pending.isNotEmpty()) {
            graph.actions.moveToVault(pending)
            graph.actions.pendingVault.value = emptyList()
        }
    }
    androidx.activity.compose.BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    val selected = items.filter { it.id in selection }

    fun open(item: VaultItem) {
        if (item.kind == FileKind.IMAGE) {
            nav.navigate(Routes.vaultImage(item.id))
            return
        }
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { graph.vault.decryptForViewing(item) }
                if (!Opener.open(context, file)) graph.actions.notify("No app on your phone can open this file")
            } catch (e: Exception) {
                graph.actions.notify(e.message ?: "Couldn't open ${item.name}")
            }
        }
    }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Outlined.Close, "Clear selection") } },
                    title = { Text("${selection.size} selected") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    actions = {
                        IconButton(onClick = {
                            graph.actions.restoreFromVault(selected)
                            selection = emptySet()
                        }) { Icon(Icons.Outlined.RestoreFromTrash, "Move out of hidden folder") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteForever, "Delete for good") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Hidden folder") },
                    navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More options") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            if (canUseFingerprint(context)) {
                                DropdownMenuItem(
                                    text = { Text(if (fingerprintOn) "Turn off fingerprint unlock" else "Turn on fingerprint unlock") },
                                    leadingIcon = { Icon(Icons.Outlined.Fingerprint, null) },
                                    onClick = {
                                        menu = false
                                        if (fingerprintOn) {
                                            graph.vault.disableBiometric()
                                            graph.biometricKey.delete()
                                            fingerprintOn = false
                                            graph.actions.notify("Fingerprint unlock is off")
                                        } else {
                                            enableFingerprint(context, graph) { fingerprintOn = it }
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(text = { Text("Change PIN") }, onClick = {
                                menu = false
                                changePin = true
                            })
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (selection.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.folder(graph.storageRoot.absolutePath, pickForVault = true)) },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text("Add files") },
                )
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(Modifier.padding(padding)) {
                EmptyState(
                    Icons.Outlined.Lock,
                    "Your hidden folder is empty",
                    "Tap Add files, or choose Move to hidden folder on any file. Files are encrypted and removed from their old place.",
                )
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp),
        ) {
            items(items.sortedByDescending { it.addedAt }, key = { it.id }) { item ->
                val isSelected = item.id in selection
                Column(
                    Modifier
                        .padding(4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .combinedClickable(
                            onClick = {
                                if (selection.isNotEmpty()) {
                                    selection = if (isSelected) selection - item.id else selection + item.id
                                } else {
                                    open(item)
                                }
                            },
                            onLongClick = { selection = if (isSelected) selection - item.id else selection + item.id },
                            onLongClickLabel = "Select",
                        )
                        .padding(6.dp),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        VaultThumb(item, Modifier.fillMaxSize())
                        if (isSelected) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text(formatSize(item.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${itemCount(selected.size)} for good?") },
            text = { Text("Hidden files don't go to Trash. They will be destroyed and can't be brought back.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    graph.actions.deleteFromVault(selected)
                    selection = emptySet()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (changePin) ChangePinDialog { changePin = false }
    if (offerFingerprint) FingerprintOfferDialog(onEnabled = { fingerprintOn = true }, onDone = onOfferDone)
}

@Composable
private fun VaultThumb(item: VaultItem, modifier: Modifier) {
    val graph = LocalGraph.current
    val bitmap by produceState<ImageBitmap?>(null, item.id) {
        if (item.kind == FileKind.IMAGE) {
            value = withContext(Dispatchers.IO) {
                try {
                    graph.vault.readBytes(item, 25_000_000L)?.let { decodeImage(it, 256) }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(16.dp)))
    } else {
        Box(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
            KindIcon(item.kind, 56.dp)
        }
    }
}

@Composable
private fun ChangePinDialog(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    @Composable
    fun field(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = { v -> if (v.length <= VaultManager.PIN_LENGTH && v.all(Char::isDigit)) onChange(v) },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Change PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                field("Current PIN", current) { current = it }
                field("New 6-digit PIN", next) { next = it }
                field("New PIN again", confirm) { confirm = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && current.length == 6 && next.length == 6 && confirm.length == 6,
                onClick = {
                    if (next != confirm) {
                        error = "The new PINs don't match"
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { graph.vault.changePin(current, next) }
                        busy = false
                        if (ok) {
                            onDismiss()
                            graph.actions.notify("PIN changed")
                        } else {
                            error = "The current PIN is wrong"
                        }
                    }
                },
            ) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** Picture from the hidden folder, decrypted in memory only. */
@Composable
fun VaultImageScreen(nav: NavController, id: String) {
    val graph = LocalGraph.current
    val unlocked by graph.vault.unlocked.collectAsStateWithLifecycle()
    val items by graph.vault.items.collectAsStateWithLifecycle()
    val item = items.firstOrNull { it.id == id }
    SecureWindow()
    LaunchedEffect(unlocked, item) { if (!unlocked || item == null) nav.popBackStack() }
    if (item == null) return
    val bitmap by produceState<ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) {
            try {
                graph.vault.readBytes(item, 200_000_000L)?.let { decodeImage(it, 2048) }
            } catch (_: Exception) {
                null
            }
        }
    }
    ZoomableViewer(
        title = item.name,
        onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = {
                graph.actions.restoreFromVault(listOf(item))
                nav.popBackStack()
            }) { Icon(Icons.Outlined.RestoreFromTrash, "Move out of hidden folder", tint = androidx.compose.ui.graphics.Color.White) }
        },
    ) { modifier ->
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = modifier)
        } else {
            Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}
