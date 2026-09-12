package com.alcohol.englishstudying.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.ui.decks.DeckScreen
import com.alcohol.englishstudying.ui.edit.WordEditScreen
import com.alcohol.englishstudying.ui.imports.ImportScreen
import com.alcohol.englishstudying.ui.library.LibraryScreen
import com.alcohol.englishstudying.ui.settings.CardFieldSettingsScreen
import com.alcohol.englishstudying.ui.settings.SettingsScreen
import com.alcohol.englishstudying.ui.stats.StatsScreen
import com.alcohol.englishstudying.ui.study.StudyScreen

object Routes {
    const val STUDY = "study"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"

    const val DECKS = "decks"
    const val IMPORT = "import"
    const val STATS = "stats"
    const val CARD_FIELDS = "card_fields"

    const val WORD_EDIT = "word_edit"
    const val ARG_WORD_ID = "wordId"
    const val WORD_EDIT_PATTERN = "$WORD_EDIT?$ARG_WORD_ID={$ARG_WORD_ID}"

    fun wordEdit(wordId: Long? = null): String =
        if (wordId == null) WORD_EDIT else "$WORD_EDIT?$ARG_WORD_ID=$wordId"
}

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val TOP_LEVEL = listOf(
    TopLevelDestination(Routes.STUDY, "学习", Icons.Filled.School),
    TopLevelDestination(Routes.LIBRARY, "词库", Icons.Filled.MenuBook),
    TopLevelDestination(Routes.SETTINGS, "设置", Icons.Filled.Settings)
)

@Composable
fun AppNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = TOP_LEVEL.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TOP_LEVEL.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    // 切页签不该把返回栈越堆越深
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.STUDY,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.STUDY) {
                StudyScreen(
                    container = container,
                    onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) }
                )
            }

            composable(Routes.LIBRARY) {
                LibraryScreen(
                    container = container,
                    onOpenWord = { id -> navController.navigate(Routes.wordEdit(id)) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onOpenDecks = { navController.navigate(Routes.DECKS) },
                    onOpenStats = { navController.navigate(Routes.STATS) }
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onOpenCardFields = { navController.navigate(Routes.CARD_FIELDS) },
                    onOpenDecks = { navController.navigate(Routes.DECKS) },
                    onOpenStats = { navController.navigate(Routes.STATS) }
                )
            }

            composable(Routes.CARD_FIELDS) {
                CardFieldSettingsScreen(container = container, onBack = navController::popBackStack)
            }

            composable(Routes.DECKS) {
                DeckScreen(container = container, onBack = navController::popBackStack)
            }

            composable(Routes.STATS) {
                StatsScreen(container = container, onBack = navController::popBackStack)
            }

            composable(Routes.IMPORT) {
                ImportScreen(
                    container = container,
                    onBack = navController::popBackStack,
                    onImported = {
                        // 导入完直接回词库看结果，而不是停在结果页
                        navController.popBackStack(Routes.LIBRARY, inclusive = false)
                    }
                )
            }

            composable(
                route = Routes.WORD_EDIT_PATTERN,
                arguments = listOf(
                    navArgument(Routes.ARG_WORD_ID) {
                        type = NavType.LongType
                        defaultValue = 0L
                    }
                )
            ) { entry ->
                val wordId = entry.arguments?.getLong(Routes.ARG_WORD_ID) ?: 0L
                WordEditScreen(
                    container = container,
                    wordId = wordId.takeIf { it > 0L },
                    onBack = navController::popBackStack
                )
            }
        }
    }
}
