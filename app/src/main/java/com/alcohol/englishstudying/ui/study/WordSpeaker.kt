package com.alcohol.englishstudying.ui.study

import android.content.Context
import android.content.res.AssetManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单词朗读。优先播 assets/pron/ 里按词名散装的真人发音（来自 k3y 词库的爱词霸音频），
 * 查不到的词回落系统 TTS。不引入依赖、不需要权限、不需要网络。
 *
 * 发音是锦上添花：assets 缺文件、引擎缺失、语言包没装、初始化失败，一律静默降级
 * 成「点了没声音」，绝不抛异常、绝不挡住学习流程。所以 [ready] 是个普通字段而不是
 * Compose 状态 —— 图标常显，哑掉就哑掉，省掉在 TTS 回调线程上写 Compose 状态的麻烦。
 */
@Stable
class WordSpeaker internal constructor(private val assets: AssetManager?) {

    @Volatile
    internal var engine: TextToSpeech? = null

    @Volatile
    internal var ready: Boolean = false

    private var player: MediaPlayer? = null

    fun speak(text: String) {
        if (playBundled(text)) return
        val tts = engine
        if (!ready || tts == null || text.isBlank()) return
        // QUEUE_FLUSH：连点同一个词时打断上一次，而不是排队念两遍
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
    }

    /** 真人发音命中返回 true；文件不存在或播放失败都返回 false，让调用方走 TTS。 */
    private fun playBundled(word: String): Boolean {
        val am = assets ?: return false
        val name = word.trim()
        if (name.isEmpty()) return false
        val path = firstExisting(am, name) ?: return false
        return try {
            am.openFd(path).use { afd ->
                val p = player ?: MediaPlayer().also { player = it }
                p.reset()
                p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                p.prepare()
                p.start()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun firstExisting(am: AssetManager, name: String): String? =
        listOf("pron/$name.mp3", "pron/${name.lowercase()}.mp3").firstOrNull { path ->
            try {
                am.open(path).close()
                true
            } catch (_: Exception) {
                false
            }
        }

    internal fun release() {
        ready = false
        // 不要先 stop()：stop() 只在 Prepared/Started/Paused/PlaybackCompleted 合法，
        // 而 [playBundled] 在 setDataSource/prepare 抛异常时是静默吞掉的，实例会留在
        // Error 状态（或被 reset() 留在 Idle）。此时 stop() 抛 IllegalStateException ——
        // 而这里跑在 DisposableEffect 的 onDispose 里，异常直接崩掉应用。
        // release() 对任何状态都合法，且会一并停掉播放，一个调用就够。
        player?.release()
        player = null
        engine?.run {
            stop()
            shutdown()
        }
        engine = null
    }
}

@Composable
fun rememberWordSpeaker(): WordSpeaker {
    val context = LocalContext.current
    val speaker = remember { WordSpeaker(context.applicationContext.assets) }

    DisposableEffect(context) {
        // TextToSpeech 的构造函数可能在返回之前就回调 onInit，那时 engine 还没赋值。
        // 用数组中转，让 onInit 拿到的一定是同一个实例，绕开这个经典竞态。
        val holder = arrayOfNulls<TextToSpeech>(1)
        val released = AtomicBoolean(false)

        val tts = TextToSpeech(context.applicationContext) { status ->
            val created = holder[0] ?: return@TextToSpeech
            // 还没挂上就已经被 dispose 了（页面秒退），别把实例漏在外面
            if (released.get()) {
                created.shutdown()
                return@TextToSpeech
            }
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech

            val result = created.setLanguage(Locale.US)
            val usable = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (usable) {
                speaker.engine = created
                speaker.ready = true
            }
        }

        holder[0] = tts

        onDispose {
            released.set(true)
            speaker.release()
        }
    }

    return speaker
}
