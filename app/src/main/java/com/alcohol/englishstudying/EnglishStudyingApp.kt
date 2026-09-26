package com.alcohol.englishstudying

import android.app.Application
import com.alcohol.englishstudying.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EnglishStudyingApp : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * 应用级作用域。
     *
     * 只用于「启动后补建词语关联」这类不该跟着任何界面生死的工作。
     * SupervisorJob 让一次失败不影响后续任务。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 关联表为空（老版本升级上来、或导入前就有词）时补建一次。
        // 不阻塞启动，失败也不影响应用可用。
        appScope.launch {
            runCatching { container.relations.ensureBuilt() }
        }
    }
}
