package app.filo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.filo.Graph
import app.filo.R
import app.filo.data.Category
import app.filo.data.ReviewKind
import app.filo.ui.theme.FiloTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FiloApp(
    graph: Graph,
    pendingRoute: StateFlow<String?> = MutableStateFlow(null),
    onRouteHandled: () -> Unit = {},
) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val home = remember { HomeController() }
    CompositionLocalProvider(LocalGraph provides graph, LocalHome provides home) {
        FiloTheme(settings.themeMode, settings.dynamicColor) {
            val granted by graph.permission.granted.collectAsStateWithLifecycle()
            if (granted) FiloNav(pendingRoute, onRouteHandled) else PermissionScreen()
        }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val DRAWER_WIDTH = 270.dp

private val tabs = listOf(
    Tab("Review", Icons.Outlined.DonutLarge),
    Tab("Browse", Icons.Outlined.Category),
    Tab("Storage", Icons.Outlined.Folder),
    Tab("Trash", Icons.Outlined.Delete),
)

private fun NavController.hasVaultOpen(): Boolean = try {
    getBackStackEntry(Routes.VAULT)
    true
} catch (_: IllegalArgumentException) {
    false
}

@Composable
private fun FiloNav(pendingRoute: StateFlow<String?>, onRouteHandled: () -> Unit) {
    val graph = LocalGraph.current
    val home = LocalHome.current
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        graph.actions.messages.collect { message ->
            val result = snackbar.showSnackbar(
                message.text,
                actionLabel = message.actionLabel,
                withDismissAction = message.actionLabel == null,
                duration = SnackbarDuration.Short, // about 4 seconds, Undo included
            )
            if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
        }
    }

    // Leaving the hidden folder (back to the rest of Filo) locks it right away.
    // Screens opened from inside it (a photo, "Add files") keep it open.
    DisposableEffect(nav) {
        val listener = NavController.OnDestinationChangedListener { controller, _, _ ->
            if (!controller.hasVaultOpen()) {
                // Files waiting for "Hide here" stay waiting only while the hidden folder is open.
                graph.actions.pendingVault.value = emptyList()
                if (graph.vault.unlocked.value) {
                    graph.vault.lock()
                    graph.vault.clearTemp()
                }
            }
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }

    val route by pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(route) {
        val target = route ?: return@LaunchedEffect
        if (target == Routes.STORAGE) home.showTab(nav, Tabs.STORAGE) else nav.navigate(target)
        onRouteHandled()
    }

    val backStack by nav.currentBackStackEntryAsState()
    val onHome = backStack?.destination?.route == Routes.HOME
    val fileDrag = remember { FileDrag() }
    // A screen change that isn't part of the drag (back button, a dialog) ends it.
    LaunchedEffect(backStack?.destination?.route) { fileDrag.cancel() }

    CompositionLocalProvider(LocalFileDrag provides fileDrag) { FileDragHost(fileDrag) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            enterTransition = { fadeIn(tween(160)) },
            exitTransition = { fadeOut(tween(160)) },
        ) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(
                Routes.FOLDER,
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("pick") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                    navArgument("highlight") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                val path = it.arguments?.getString("path") ?: graph.storageRoot.absolutePath
                FolderScreen(
                    nav,
                    File(path),
                    isTab = false,
                    pickForVault = it.arguments?.getBoolean("pick") == true,
                    highlight = it.arguments?.getString("highlight"),
                )
            }
            composable(Routes.CATEGORY, arguments = listOf(navArgument("category") { type = NavType.StringType })) {
                val category = Category.valueOf(it.arguments?.getString("category") ?: Category.IMAGES.name)
                CategoryScreen(nav, category)
            }
            composable(Routes.REVIEW_LIST, arguments = listOf(navArgument("kind") { type = NavType.StringType })) {
                ReviewListScreen(nav, ReviewKind.valueOf(it.arguments?.getString("kind") ?: ReviewKind.LARGE.name))
            }
            composable(Routes.SEARCH) { SearchScreen(nav) }
            composable(Routes.FAVORITES) { FavoritesScreen(nav) }
            composable(Routes.VAULT) { VaultScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.ABOUT) { AboutScreen(nav) }
            composable(Routes.APPS) { AppsScreen(nav) }
            composable(Routes.LICENSE, arguments = listOf(navArgument("which") { type = NavType.StringType })) {
                LicenseScreen(nav, it.arguments?.getString("which") ?: "gpl")
            }
            composable(Routes.IMAGE, arguments = listOf(navArgument("path") { type = NavType.StringType })) {
                ImageViewerScreen(nav, File(it.arguments?.getString("path").orEmpty()))
            }
            composable(Routes.VAULT_IMAGE, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                VaultImageScreen(nav, it.arguments?.getString("id").orEmpty())
            }
            composable(Routes.NETWORK) { NetworkScreen(nav) }
            composable(Routes.SERVER, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                ServerScreen(nav, it.arguments?.getString("id").orEmpty())
            }
            composable(
                Routes.SERVER_EDIT,
                arguments = listOf(
                    navArgument("id") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { ServerEditScreen(nav, it.arguments?.getString("id")) }
            composable(
                Routes.SERVER_IMAGE,
                arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("path") { type = NavType.StringType }),
            ) { ServerImageScreen(nav, it.arguments?.getString("id").orEmpty(), it.arguments?.getString("path").orEmpty()) }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (onHome) 80.dp else 0.dp),
        )
    } }

    OperationDialog()
}

/** The four tabs. Swipe left and right, or tap them at the bottom. */
@Composable
private fun HomeScreen(nav: NavController) {
    val graph = LocalGraph.current
    val home = LocalHome.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = Tabs.BROWSE) { Tabs.COUNT }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var barStartPage by remember { mutableIntStateOf(Tabs.BROWSE) }
    var barDrag by remember { mutableFloatStateOf(0f) }

    val requested by home.requestedPage.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        val page = requested ?: return@LaunchedEffect
        pager.animateScrollToPage(page)
        home.requestedPage.value = null
    }
    LaunchedEffect(Unit) { home.drawerRequests.collect { drawer.open() } }

    // Back: close the menu, then return to Browse, then leave Filo.
    BackHandler(enabled = !drawer.isOpen && pager.currentPage != Tabs.BROWSE) {
        scope.launch { pager.animateScrollToPage(Tabs.BROWSE) }
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen, // swiping is for the tabs; ☰ opens the menu
        drawerContent = {
            // Only build the menu while it's showing, so screen readers don't find hidden items.
            if (drawer.currentValue == DrawerValue.Open || drawer.targetValue == DrawerValue.Open) {
                FiloDrawer { route ->
                    scope.launch {
                        drawer.close()
                        nav.navigate(route)
                    }
                }
            } else {
                ModalDrawerSheet(Modifier.width(DRAWER_WIDTH)) {}
            }
        },
    ) {
        Scaffold(
            bottomBar = {
                // Swipe along the bar to switch tabs, one tab per swipe (the pages themselves don't swipe).
                // Swipe left for the tab on the right, swipe right for the tab on the left.
                NavigationBar(
                    Modifier
                        .testTag("tab_bar")
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                val page = pager.layoutInfo.pageSize.toFloat().coerceAtLeast(1f)
                                val next = (barDrag + delta * 1.5f).coerceIn(-page, page)
                                val step = next - barDrag
                                barDrag = next
                                pager.dispatchRawDelta(-step) // the page follows the finger, at most one tab
                            },
                            onDragStarted = {
                                barStartPage = pager.currentPage
                                barDrag = 0f
                            },
                            onDragStopped = { velocity ->
                                val page = pager.layoutInfo.pageSize.toFloat().coerceAtLeast(1f)
                                val target = when {
                                    velocity < -500f || barDrag < -page * 0.2f -> barStartPage + 1
                                    velocity > 500f || barDrag > page * 0.2f -> barStartPage - 1
                                    else -> barStartPage
                                }.coerceIn(0, Tabs.COUNT - 1)
                                barDrag = 0f
                                pager.animateScrollToPage(target)
                            },
                        ),
                ) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = pager.targetPage == index,
                            onClick = { scope.launch { pager.animateScrollToPage(index) } },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            HorizontalPager(
                state = pager,
                key = { it },
                userScrollEnabled = false,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            ) { page ->
                val active = pager.currentPage == page
                when (page) {
                    Tabs.REVIEW -> ReviewScreen(nav)
                    Tabs.BROWSE -> BrowseScreen(nav)
                    Tabs.STORAGE -> FolderScreen(nav, graph.storageRoot, isTab = true, active = active)
                    else -> TrashScreen(active)
                }
            }
        }
    }
}

@Composable
private fun FiloDrawer(onNavigate: (String) -> Unit) {
    ModalDrawerSheet(Modifier.width(DRAWER_WIDTH)) {
        Row(Modifier.padding(start = 24.dp, top = 28.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(colorResource(R.color.filo_teal))) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Filo", style = MaterialTheme.typography.titleLarge)
                Text("Your files stay on your phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        @Composable
        fun item(label: String, icon: ImageVector, route: String) {
            NavigationDrawerItem(
                label = { Text(label) },
                icon = { Icon(icon, contentDescription = null) },
                selected = false,
                onClick = { onNavigate(route) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        item("Favorites", Icons.Outlined.Star, Routes.FAVORITES)
        item("Hidden folder", Icons.Outlined.Lock, Routes.VAULT)
        if (LocalGraph.current.settings.state.collectAsStateWithLifecycle().value.networkServers) {
            item("Network", Icons.Outlined.Lan, Routes.NETWORK)
        }
        HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
        item("Settings", Icons.Outlined.Settings, Routes.SETTINGS)
        item("About", Icons.Outlined.Info, Routes.ABOUT)
    }
}

/** Progress for copying, moving, zipping and hiding. Only appears if the task takes a moment. */
@Composable
private fun OperationDialog() {
    val graph = LocalGraph.current
    val operation by graph.actions.operation.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(operation != null) {
        visible = false
        if (operation != null) {
            delay(350)
            visible = true
        }
    }
    val op = operation
    if (op != null && visible) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(op.title + "…") },
            text = {
                Column {
                    val progress = op.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("${(progress * 100).toInt()} %" + (op.detail?.let { " · $it" } ?: ""))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { graph.actions.cancel() }) { Text("Cancel") } },
        )
    }
}
