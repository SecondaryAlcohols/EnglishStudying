package com.alcohol.englishstudying.di

import android.content.Context
import com.alcohol.englishstudying.data.imports.WordFileReader
import com.alcohol.englishstudying.data.imports.WordImporter
import com.alcohol.englishstudying.data.local.AppDatabase
import com.alcohol.englishstudying.data.prefs.SettingsRepository
import com.alcohol.englishstudying.data.repo.DeckRepository
import com.alcohol.englishstudying.data.repo.StudyRepository
import com.alcohol.englishstudying.data.repo.WordRepository

/**
 * 手写的依赖容器。
 *
 * 没有引 Hilt：这个应用的依赖图是一棵深度为 2 的树，为此多加一个 KSP 处理器
 * （并且要跟 Kotlin 版本严格配对）不划算。全部用 lazy，首次访问才建。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val words: WordRepository by lazy { WordRepository(database) }
    val decks: DeckRepository by lazy { DeckRepository(database) }
    val study: StudyRepository by lazy { StudyRepository(database) }
    val importer: WordImporter by lazy { WordImporter(database) }
    val fileReader: WordFileReader by lazy { WordFileReader(appContext) }
}
