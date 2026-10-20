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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.semantics.onLongClick
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
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.collectAsState
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
import android.util.LruCache
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import app.filo.data.FileEntry
import app.filo.data.NaturalOrder
import app.filo.data.SortField
import app.filo.data.SortOrder
import app.filo.data.ViewLevel
import app.filo.data.ViewPrefs
import app.filo.data.sortedBy
import java.io.File
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

/**
 * Counts how many hidden-folder screens are showing. Screens overlap while moving between them
 * (the grid and a photo), so the protection stays on until the *last* one closes.
 */
internal object SecureScreens {
    private val counts = java.util.WeakHashMap<Activity, Int>()

    fun enter(activity: Activity) {
        val n = (counts[activity] ?: 0) + 1
        counts[activity] = n
        if (n == 1) apply(activity, secure = true)
    }

    fun leave(activity: Activity) {
        val n = ((counts[activity] ?: 1) - 1).coerceAtLeast(0)
        counts[activity] = n
        if (n == 0) apply(activity, secure = false)
    }

    fun isSecure(activity: Activity) = (counts[activity] ?: 0) > 0

    private fun apply(activity: Activity, secure: Boolean) {
        if (secure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Second, independent block: Android 13+ shows a plain card in recent apps instead of a screenshot.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!secure)
        }
    }
}

/** No screenshots, screen recording or recent-apps preview while the hidden folder is visible. */
@Composable
fun SecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.let(SecureScreens::enter)
        onDispose { activity?.let(SecureScreens::leave) }
    }
}

/**
 * Covers the hidden folder as soon as Filo isn't the app in front, e.g. in the recent-apps view,
 * which shows Filo live (screenshot blocking can't hide a live view). Call it last, so it's on top.
 */
@Composable
fun PrivacyCurtain(lifecycle: androidx.lifecycle.Lifecycle? = null) {
    val source = lifecycle ?: (LocalContext.current.findActivity() as? androidx.lifecycle.LifecycleOwner)?.lifecycle ?: return
    val state by source.currentStateFlow.collectAsState()
    if (state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("privacy_curtain")
            .semantics { contentDescription = "Filo" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
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
    if (setUp && unlocked) {
        VaultContent(nav, offerFingerprint = justCreated, onOfferDone = { justCreated = false })
        PrivacyCurtain()
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
                    "Unlock to hide ${itemCount(pending.size)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            if (!setUp) VaultSetup(onCreated = { justCreated = true }) else VaultUnlock()
        }
    }
    PrivacyCurtain()
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

// ---------------------------------------------------------------- Inside the hidden folder

private const val ITEM = "vault:"
private const val DIR = "vaultdir:"
private val VAULT_ROOT = File("/vault")

/** Pictures first as a grid, newest first, until you change it (per folder). */
private val VAULT_DEFAULTS = ViewPrefs(ViewLevel.GRID_3, SortOrder(SortField.DATE, ascending = false))

/** Files and folders directly inside [folder], as list entries. */
internal fun vaultEntries(items: List<VaultItem>, folders: Set<String>, folder: String, showHidden: Boolean = true): List<FileEntry> {
    fun visible(name: String) = showHidden || !name.startsWith(".")
    val children = folders.filter { it.isNotEmpty() && visible(VaultManager.nameOf(it)) }.groupBy { VaultManager.parentOf(it) }
    val filesIn = items.filter { visible(it.name) }.groupBy { it.folder }
    val dirs = children[folder].orEmpty().map { path ->
        FileEntry(
            path = DIR + path,
            name = VaultManager.nameOf(path),
            isDirectory = true,
            size = 0,
            lastModified = items.filter { VaultManager.isInside(it.folder, path) }.maxOfOrNull { it.addedAt } ?: 0L,
            childCount = filesIn[path].orEmpty().size + children[path].orEmpty().size,
        )
    }
    val files = filesIn[folder].orEmpty().map { FileEntry(ITEM + it.id, it.name, false, it.size, it.addedAt) }
    return dirs + files
}

private fun vaultFolderOf(path: String) = if (path.startsWith(DIR)) path.removePrefix(DIR) else null

/** A part of the hidden folder's path bar back to a folder path ("" is the top). */
private fun vaultPathOf(file: File) = file.absolutePath.removePrefix(VAULT_ROOT.absolutePath).trim('/')

/** Folder paths sorted like a tree: every folder right after its parent. */
private fun treeOrder(folders: Collection<String>): List<String> = folders.sortedWith { a, b ->
    val x = a.split('/')
    val y = b.split('/')
    for (i in 0 until minOf(x.size, y.size)) {
        val c = NaturalOrder.compare(x[i], y[i])
        if (c != 0) return@sortedWith c
    }
    x.size - y.size
}

/** Previews of hidden pictures, decrypted in memory and kept only while the folder is open. */
private class VaultThumbs(private val vault: VaultManager, private val lookup: () -> Map<String, VaultItem>) : ThumbProvider {
    private val cache = LruCache<String, ImageBitmap>(200)

    @Composable
    override fun Thumb(entry: FileEntry, size: Dp, modifier: Modifier, corner: Dp) {
        val item = lookup()[entry.path.removePrefix(ITEM)]
        val bitmap by produceState(cache.get(entry.path), entry.path) {
            if (value == null && item != null && item.kind == FileKind.IMAGE) {
                value = withContext(Dispatchers.IO) {
                    try {
                        vault.readBytes(item, 25_000_000L)?.let { decodeImage(it, 256) }
                    } catch (_: Exception) {
                        null
                    }
                }
                value?.let { cache.put(entry.path, it) }
            }
        }
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(RoundedCornerShape(corner)))
        } else {
            KindIcon(entry.kind, size, modifier, corner)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultContent(nav: NavController, offerFingerprint: Boolean, onOfferDone: () -> Unit) {
    val graph = LocalGraph.current
    val vault = graph.vault
    val actions = graph.actions
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by vault.items.collectAsStateWithLifecycle()
    val folders by vault.folders.collectAsStateWithLifecycle()
    val folder by vault.openFolder.collectAsStateWithLifecycle()
    val pending by actions.pendingVault.collectAsStateWithLifecycle()
    val views by graph.views.all.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val viewKey = remember(folder) { vault.viewKey(folder) }
    val view = views[viewKey] ?: VAULT_DEFAULTS
    fun setView(next: ViewPrefs) = graph.views.set(viewKey, next)

    var selection by remember(folder) { mutableStateOf(emptySet<String>()) }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var changePin by remember { mutableStateOf(false) }
    var fingerprintOn by remember { mutableStateOf(vault.biometricEnabled) }
    var sheetFor by remember { mutableStateOf<FileEntry?>(null) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<List<String>?>(null) }
    var deleting by remember { mutableStateOf<List<String>?>(null) }

    val byId = remember(items) { items.associateBy { it.id } }
    val currentById by rememberUpdatedState(byId)
    val thumbs = remember { VaultThumbs(vault) { currentById } }
    val entries = remember(items, folders, folder, view.sort, settings.showHidden) {
        vaultEntries(items, folders, folder, settings.showHidden).sortedBy(view.sort)
    }

    fun itemOf(path: String) = if (path.startsWith(ITEM)) byId[path.removePrefix(ITEM)] else null
    fun goTo(path: String) {
        vault.openFolder.value = path
    }

    // The open folder was moved or deleted meanwhile: go to the nearest one that still exists.
    LaunchedEffect(folder, folders) {
        var f = folder
        while (f.isNotEmpty() && f !in folders) f = VaultManager.parentOf(f)
        if (f != folder) goTo(f)
    }
    LaunchedEffect(entries) {
        if (selection.isNotEmpty()) {
            val present = entries.map { it.path }.toSet()
            selection = selection.filter { it in present }.toSet()
        }
    }
    androidx.activity.compose.BackHandler(enabled = selection.isNotEmpty() || folder.isNotEmpty()) {
        if (selection.isNotEmpty()) selection = emptySet() else goTo(VaultManager.parentOf(folder))
    }

    fun open(entry: FileEntry) {
        vaultFolderOf(entry.path)?.let {
            goTo(it)
            return
        }
        val item = itemOf(entry.path) ?: return
        if (item.kind == FileKind.IMAGE) {
            nav.navigate(Routes.vaultImage(item.id))
            return
        }
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { vault.decryptForViewing(item) }
                if (!Opener.open(context, file)) actions.notify("No app on your phone can open this file")
            } catch (e: Exception) {
                actions.notify(e.message ?: "Couldn't open ${item.name}")
            }
        }
    }

    fun dropInto(destination: String, paths: Set<String>) {
        val all = vault.items.value.associateBy { it.id }
        actions.moveInVault(paths.mapNotNull { p -> if (p.startsWith(ITEM)) all[p.removePrefix(ITEM)] else null }, paths.mapNotNull(::vaultFolderOf), destination)
        selection = emptySet()
    }

    fun moveOut(paths: Collection<String>) {
        actions.restoreFromVault(paths.mapNotNull(::itemOf), paths.mapNotNull(::vaultFolderOf))
        selection = emptySet()
    }

    CompositionLocalProvider(LocalThumbs provides thumbs) {
        Scaffold(
            topBar = {
                if (selection.isNotEmpty()) {
                    var more by remember { mutableStateOf(false) }
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Outlined.Close, "Clear selection") } },
                        title = { Text("${selection.size} selected") },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        actions = {
                            IconButton(onClick = { selection = entries.map { it.path }.toSet() }) { Icon(Icons.Outlined.SelectAll, "Select all") }
                            IconButton(onClick = { moveOut(selection) }) { Icon(Icons.Outlined.LockOpen, "Move out of hidden folder") }
                            IconButton(onClick = { deleting = selection.toList() }) { Icon(Icons.Outlined.DeleteForever, "Delete for good") }
                            IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "More actions") }
                            DropdownMenu(more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(
                                    text = { Text("Move to folder…") },
                                    leadingIcon = { @Suppress("DEPRECATION") Icon(Icons.Outlined.DriveFileMove, null) },
                                    onClick = {
                                        more = false
                                        moving = selection.toList()
                                    },
                                )
                                if (selection.size == 1) {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                        onClick = {
                                            more = false
                                            renaming = entries.firstOrNull { it.path in selection }
                                        },
                                    )
                                }
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(if (folder.isEmpty()) "Hidden folder" else VaultManager.nameOf(folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        navigationIcon = {
                            IconButton(onClick = { if (folder.isEmpty()) nav.popBackStack() else goTo(VaultManager.parentOf(folder)) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { setView(view.copy(level = if (view.level.isGrid) ViewLevel.LIST else ViewLevel.GRID_3)) }) {
                                if (view.level.isGrid) Icon(Icons.AutoMirrored.Outlined.List, "Show as list") else Icon(Icons.Outlined.GridView, "Show as grid")
                            }
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                            SortMenu(sortMenu, view.sort, onDismiss = { sortMenu = false }, onChange = { setView(view.copy(sort = it)) })
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More options") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("New folder") },
                                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                    onClick = {
                                        menu = false
                                        newFolder = true
                                    },
                                )
                                if (canUseFingerprint(context)) {
                                    DropdownMenuItem(
                                        text = { Text(if (fingerprintOn) "Turn off fingerprint unlock" else "Turn on fingerprint unlock") },
                                        leadingIcon = { Icon(Icons.Outlined.Fingerprint, null) },
                                        onClick = {
                                            menu = false
                                            if (fingerprintOn) {
                                                vault.disableBiometric()
                                                graph.biometricKey.delete()
                                                fingerprintOn = false
                                                actions.notify("Fingerprint unlock is off")
                                            } else {
                                                enableFingerprint(context, graph) { fingerprintOn = it }
                                            }
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Change PIN") },
                                    leadingIcon = { Icon(Icons.Outlined.Password, null) },
                                    onClick = {
                                        menu = false
                                        changePin = true
                                    },
                                )
                            }
                        },
                    )
                }
            },
            bottomBar = {
                // Coming from "Move to hidden folder": open the folder it should go in, then Hide here.
                if (pending.isNotEmpty() && selection.isEmpty()) {
                    ActionBar(
                        text = "${itemCount(pending.size)} to hide",
                        confirm = "Hide here",
                        enabled = true,
                        onCancel = {
                            actions.pendingVault.value = emptyList()
                            nav.popBackStack()
                        },
                        onConfirm = {
                            actions.moveToVault(pending, into = folder)
                            actions.pendingVault.value = emptyList()
                        },
                    )
                }
            },
            floatingActionButton = {
                if (selection.isEmpty() && pending.isEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { nav.navigate(Routes.folder(graph.storageRoot.absolutePath, pickForVault = true)) },
                        icon = { Icon(Icons.Outlined.Add, null) },
                        text = { Text("Add files") },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (folder.isNotEmpty()) {
                    Breadcrumbs(
                        "Hidden folder",
                        VAULT_ROOT,
                        File(VAULT_ROOT, folder),
                        onOpen = { goTo(vaultPathOf(it)) },
                        drop = CrumbDrop(VAULT_SPACE, ::vaultPathOf) { target, paths -> dropInto(vaultPathOf(target), paths) },
                    )
                }
                val paneDrop = PaneDrop(VAULT_SPACE, folder, if (folder.isEmpty()) "Hidden folder" else VaultManager.nameOf(folder)) { target, paths ->
                    dropInto(target?.let { vaultFolderOf(it.path) } ?: folder, paths)
                }
                when {
                    entries.isEmpty() && folder.isEmpty() -> EmptyState(
                        Icons.Outlined.Lock,
                        "Your hidden folder is empty",
                        "Tap Add files, or choose Move to hidden folder on any file or folder. Everything is encrypted and removed from its old place.",
                    )
                    entries.isEmpty() -> Box(Modifier.fillMaxSize().dropHere(paneDrop)) {
                        EmptyState(
                            Icons.Outlined.FolderOpen,
                            "This folder is empty",
                            if (pending.isNotEmpty()) "Tap Hide here to put them in this folder." else "Tap Add files to hide files right here, or move hidden files into it.",
                        )
                    }
                    else -> key(folder) {
                        FileListPane(
                            entries = entries,
                            level = view.level,
                            selection = selection,
                            favorites = emptySet(),
                            onOpen = ::open,
                            onSelectionChange = { selection = it },
                            onMore = { sheetFor = it },
                            onLevelChange = { setView(view.copy(level = it)) },
                            drop = paneDrop,
                        )
                    }
                }
            }
        }

        sheetFor?.let { entry ->
            VaultEntrySheet(
                entry = entry,
                onDismiss = { sheetFor = null },
                onMoveOut = { moveOut(listOf(entry.path)) },
                onMove = { moving = listOf(entry.path) },
                onRename = { renaming = entry },
                onDelete = { deleting = listOf(entry.path) },
            )
        }
    }

    renaming?.let { entry ->
        val path = vaultFolderOf(entry.path)
        val item = itemOf(entry.path)
        NameDialog(
            title = "Rename",
            initial = entry.name,
            selectBaseName = !entry.isDirectory,
            confirm = "Rename",
            exists = { name ->
                if (path != null) VaultManager.join(VaultManager.parentOf(path), name) in folders
                else items.any { it.folder == item?.folder && it.name == name && it.id != item.id }
            },
            onDismiss = { renaming = null },
            onConfirm = {
                actions.renameInVault(item, path, it)
                selection = emptySet()
            },
        )
    }

    if (newFolder) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirm = "Create",
            exists = { VaultManager.join(folder, it) in folders },
            onDismiss = { newFolder = false },
            onConfirm = { actions.createVaultFolder(folder, it) },
        )
    }

    moving?.let { paths ->
        MoveInVaultDialog(
            folders = folders,
            current = folder,
            movingFolders = paths.mapNotNull(::vaultFolderOf),
            onDismiss = { moving = null },
            onMove = { destination ->
                actions.moveInVault(paths.mapNotNull(::itemOf), paths.mapNotNull(::vaultFolderOf), destination)
                selection = emptySet()
            },
        )
    }

    deleting?.let { paths ->
        val withFolders = paths.any { it.startsWith(DIR) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${itemCount(paths.size)} for good?") },
            text = {
                Text(
                    "Hidden files don't go to Trash. They will be destroyed and can't be brought back." +
                        if (withFolders) " Everything inside the folders is deleted too." else "",
                )
            },
            confirmButton = {
                Button(onClick = {
                    deleting = null
                    actions.deleteFromVault(paths.mapNotNull(::itemOf), paths.mapNotNull(::vaultFolderOf))
                    selection = emptySet()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (changePin) ChangePinDialog { changePin = false }
    if (offerFingerprint) FingerprintOfferDialog(onEnabled = { fingerprintOn = true }, onDone = onOfferDone)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultEntrySheet(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onMoveOut: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            ListItem(
                leadingContent = { FileThumb(entry, 44.dp) },
                headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(entry.subtitle()) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            @Composable
            fun action(text: String, icon: ImageVector, run: () -> Unit) {
                ListItem(
                    leadingContent = { Icon(icon, null) },
                    headlineContent = { Text(text) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickableRow {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            onDismiss()
                            run()
                        }
                    },
                )
            }
            action("Move out of hidden folder", Icons.Outlined.LockOpen, onMoveOut)
            @Suppress("DEPRECATION")
            action("Move to folder…", Icons.Outlined.DriveFileMove, onMove)
            action("Rename", Icons.Outlined.Edit, onRename)
            action("Delete for good", Icons.Outlined.DeleteForever, onDelete)
        }
    }
}

/** Pick a folder inside the hidden folder. Folders can't go into themselves. */
@Composable
private fun MoveInVaultDialog(
    folders: Set<String>,
    current: String,
    movingFolders: List<String>,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
) {
    val options = remember(folders) { listOf("") + treeOrder(folders.filter { it.isNotEmpty() }) }
    var target by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(options) { option ->
                    val blocked = movingFolders.any { VaultManager.isInside(option, it) }
                    val depth = if (option.isEmpty()) 0 else option.count { it == '/' } + 1
                    val chosen = option == target
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable(enabled = !blocked) { target = option }
                            .padding(start = 8.dp + 20.dp * depth, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = if (blocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.primary
                        Icon(if (option.isEmpty()) Icons.Outlined.Lock else Icons.Outlined.Folder, null, tint = tint)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (option.isEmpty()) "Hidden folder" else VaultManager.nameOf(option),
                            color = if (blocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = target != null && target != current,
                onClick = {
                    onDismiss()
                    onMove(target!!)
                },
            ) { Text("Move here") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

/** Pictures from the hidden folder, decrypted in memory only. Swipe between them. */
@Composable
fun VaultImageScreen(nav: NavController, id: String) {
    val graph = LocalGraph.current
    val unlocked by graph.vault.unlocked.collectAsStateWithLifecycle()
    val items by graph.vault.items.collectAsStateWithLifecycle()
    // Swipe through the pictures of the same folder, in the order that folder is sorted.
    val folder = remember { items.firstOrNull { it.id == id }?.folder ?: "" }
    val sort = remember { graph.views.get(graph.vault.viewKey(folder), VAULT_DEFAULTS).sort }
    val pictures = remember(items) {
        val byId = items.associateBy { it.id }
        vaultEntries(items.filter { it.kind == FileKind.IMAGE }, emptySet(), folder).sortedBy(sort).mapNotNull { byId[it.path.removePrefix(ITEM)] }
    }
    val start = remember { pictures.indexOfFirst { it.id == id }.coerceAtLeast(0) }
    SecureWindow()
    LaunchedEffect(unlocked, pictures.isEmpty()) { if (!unlocked || pictures.isEmpty()) nav.popBackStack() }
    if (pictures.isEmpty()) return
    PhotoPager(
        count = pictures.size,
        initialPage = start.coerceAtMost(pictures.lastIndex),
        title = { pictures[it].name },
        onBack = { nav.popBackStack() },
        actions = { page ->
            IconButton(onClick = { graph.actions.restoreFromVault(listOf(pictures[page])) }) {
                Icon(Icons.Outlined.LockOpen, "Move out of hidden folder", tint = androidx.compose.ui.graphics.Color.White)
            }
        },
    ) { page, modifier ->
        val item = pictures[page]
        val bitmap by produceState<ImageBitmap?>(null, item.id) {
            value = withContext(Dispatchers.IO) {
                try {
                    graph.vault.readBytes(item, 200_000_000L)?.let { decodeImage(it, 2048) }
                } catch (_: Exception) {
                    null
                }
            }
        }
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = modifier)
        } else {
            Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
    PrivacyCurtain()
}
