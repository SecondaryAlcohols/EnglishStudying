package com.alcohol.englishstudying.ui.nav

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.alcohol.englishstudying.ui.detail.WordDetailScreen
import com.alcohol.englishstudying.ui.edit.WordEditScreen
import com.alcohol.englishstudying.ui.enhance.EnhanceScreen
import com.alcohol.englishstudying.ui.imports.ImportScreen
import com.alcohol.englishstudying.ui.library.LibraryScreen
import com.alcohol.englishstudying.ui.settings.CardFieldSettingsScreen
import com.alcohol.englishstudying.ui.settings.SettingsScreen
import com.alcohol.englishstudying.ui.stats.StatsScreen
import com.alcohol.englishstudying.ui.study.StudyHomeScreen
import com.alcohol.englishstudying.ui.study.StudyScreen
import com.alcohol.englishstudying.ui.study.StudyViewModel

object Routes {
    const val STUDY = "study"
    const val LIBRARY = "library"
    const val ENHANCE = "enhance"
    const val SETTINGS = "settings"

    /**
     * 卡片界面。**故意不放进 [TOP_LEVEL]** —— 它一不是页签，
     * 二正因为不在 TOP_LEVEL 里，底部标签栏在它上面天然不显示，
     * 不需要任何「进学习页就隐藏底栏」的额外状态。
     */
    const val STUDY_SESSION = "study_session"

    const val DECKS = "decks"
    const val IMPORT = "import"
    const val STATS = "stats"
    const val CARD_FIELDS = "card_fields"

    const val WORD_EDIT = "word_edit"
    const val ARG_WORD_ID = "wordId"
    const val WORD_EDIT_PATTERN = "$WORD_EDIT?$ARG_WORD_ID={$ARG_WORD_ID}"

    /** 只读单词详情页。wordId 是路径参数，必有值。 */
    const val WORD_DETAIL = "word/{$ARG_WORD_ID}"

    fun wordDetail(wordId: Long): String = "word/$wordId"

    fun wordEdit(wordId: Long? = null): String =
        if (wordId == null) WORD_EDIT else "$WORD_EDIT?$ARG_WORD_ID=$wordId"
}

/**
 * 切顶层页签。
 *
 * 关键在回到**起始页**的那条分支：不能和别的页签共用一套 popUpTo + restoreState。
 * `popUpTo(起始页) { saveState = true }` 保存下来的栈，在 Navigation 内部是以
 * **起始页的 id** 为键的；之后再 `restoreState = true` 导航到起始页，就会把
 * 「上一次被保存的那个页签」的栈恢复出来 —— 表现成点「学习」却跳到了「词库」，
 * 而且因为栈被恢复，点几次都出不去。
 *
 * 所以回起始页时只做弹栈：起始页的返回栈条目始终留在栈底、从不销毁，
 * 它的 ViewModel 也就跟着活下来，学习会话不会因为切页签而重置。
 */
private fun NavHostController.switchTopLevel(targetRoute: String) {
    val start = graph.findStartDestination()
    if (start.route == targetRoute) {
        popBackStack(start.id, inclusive = false)
    } else {
        navigate(targetRoute) {
            popUpTo(start.id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val TOP_LEVEL = listOf(
    TopLevelDestination(Routes.STUDY, "学习", Icons.Filled.School),
    TopLevelDestination(Routes.LIBRARY, "词库", Icons.Filled.MenuBook),
    TopLevelDestination(Routes.ENHANCE, "增强", Icons.Filled.Bolt),
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
                            onClick = { navController.switchTopLevel(destination.route) },
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
            // consumeWindowInsets 是这里的关键：每个页面自己还套了一层 Scaffold，
            // 而它们默认也会去处理系统栏。不把已经花掉的 inset 标记成「已消费」，
            // 两个 Scaffold 会各加一次状态栏高度，标题就比状态栏低一整条。
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable(Routes.STUDY) { backStackEntry ->
                val studyViewModel: StudyViewModel = viewModel(
                    viewModelStoreOwner = backStackEntry,
                    factory = StudyViewModel.factory(container)
                )
                StudyHomeScreen(
                    container = container,
                    studyViewModel = studyViewModel,
                    onStartStudy = {
                        if (studyViewModel.state.value.isPracticeMode) {
                            studyViewModel.restoreRegularSession()
                        }
                        navController.navigate(Routes.STUDY_SESSION)
                    },
                    onStartEarlyReview = {
                        studyViewModel.startSession(includeCooling = true)
                        navController.navigate(Routes.STUDY_SESSION)
                    },
                    onStartExtraNewStudy = {
                        studyViewModel.startSession(extraNewWords = true)
                        navController.navigate(Routes.STUDY_SESSION)
                    },
                    onStartPractice = {
                        studyViewModel.startPracticeSession()
                        navController.navigate(Routes.STUDY_SESSION)
                    },
                    onOpenImport = { navController.navigate(Routes.IMPORT) }
                )
            }

            composable(Routes.STUDY_SESSION) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(Routes.STUDY)
                }
                val studyViewModel: StudyViewModel = viewModel(
                    viewModelStoreOwner = parentEntry,
                    factory = StudyViewModel.factory(container)
                )
                StudyScreen(
                    container = container,
                    viewModel = studyViewModel,
                    onBack = navController::popBackStack,
                    // 走和底部页签同一条路径，避免出现「从这个入口进词库后，
                    // 再点学习页签回不去」这种两套导航语义不一致的问题
                    onOpenLibrary = { navController.switchTopLevel(Routes.LIBRARY) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    // 点卡片上的关联词进只读详情页，看完返回回到同一张卡
                    onOpenWord = { id -> navController.navigate(Routes.wordDetail(id)) }
                )
            }

            composable(Routes.LIBRARY) {
                LibraryScreen(
                    container = container,
                    onOpenWord = { id -> navController.navigate(Routes.wordDetail(id)) },
                    onAddWord = { navController.navigate(Routes.wordEdit()) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onOpenDecks = { navController.navigate(Routes.DECKS) },
                    onOpenStats = { navController.navigate(Routes.STATS) }
                )
            }

            composable(Routes.ENHANCE) {
                EnhanceScreen(container = container)
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

            composable(
                route = Routes.WORD_DETAIL,
                arguments = listOf(
                    navArgument(Routes.ARG_WORD_ID) { type = NavType.LongType }
                )
            ) { entry ->
                val wordId = entry.arguments?.getLong(Routes.ARG_WORD_ID) ?: 0L
                WordDetailScreen(
                    container = container,
                    wordId = wordId,
                    onBack = navController::popBackStack,
                    onEdit = { navController.navigate(Routes.wordEdit(wordId.takeIf { it > 0L })) },
                    onOpenWord = { id -> navController.navigate(Routes.wordDetail(id)) }
                )
            }
        }
    }
}
