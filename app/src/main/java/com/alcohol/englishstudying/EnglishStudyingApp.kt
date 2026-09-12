package com.alcohol.englishstudying

import android.app.Application
import com.alcohol.englishstudying.di.AppContainer

class EnglishStudyingApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
