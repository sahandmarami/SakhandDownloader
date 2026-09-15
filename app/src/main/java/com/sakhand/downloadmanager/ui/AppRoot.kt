package com.sakhand.downloadmanager.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sakhand.downloadmanager.engine.DownloadManager
import com.sakhand.downloadmanager.ui.screens.AboutScreen
import com.sakhand.downloadmanager.ui.screens.DownloadsScreen
import com.sakhand.downloadmanager.ui.screens.HomeScreen
import com.sakhand.downloadmanager.ui.theme.AppTheme
import com.sakhand.downloadmanager.ui.theme.Purple
import kotlinx.coroutines.launch

private data class BottomItem(val route: String, val label: String, val icon: ImageVector)

// رابط ساده با فقط دو تب — «درباره ما» از آیکون بالای صفحه اصلی باز می‌شود
private val bottomItems = listOf(
    BottomItem("home", "خانه", Icons.Rounded.Home),
    BottomItem("downloads", "دانلودها", Icons.Rounded.Download)
)

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: "home"

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val onMessage: (String) -> Unit = { msg ->
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    val items by DownloadManager.items.collectAsStateWithLifecycle()
    val colors = AppTheme.colors

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colors.background,
        bottomBar = {
            if (currentRoute != "about") {
                NavigationBar(containerColor = colors.surface) {
                    bottomItems.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    launchSingleTop = true
                                    popUpTo("home") { saveState = true }
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Purple,
                                selectedTextColor = Purple,
                                indicatorColor = Purple.copy(alpha = 0.16f),
                                unselectedIconColor = colors.textSecondary,
                                unselectedTextColor = colors.textSecondary
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                HomeScreen(
                    items = items,
                    onMessage = onMessage,
                    onSeeAllDownloads = { navController.navigate("downloads") { launchSingleTop = true } },
                    onOpenAbout = { navController.navigate("about") { launchSingleTop = true } }
                )
            }
            composable("downloads") {
                DownloadsScreen(items = items)
            }
            composable("about") {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
