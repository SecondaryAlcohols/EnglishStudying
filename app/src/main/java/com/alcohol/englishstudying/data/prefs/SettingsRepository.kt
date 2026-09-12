package com.alcohol.englishstudying.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alcohol.englishstudying.domain.model.CardField
import com.alcohol.englishstudying.domain.model.CardFieldConfig
import com.alcohol.englishstudying.domain.model.CardFieldConfigDefaults
import com.alcohol.englishstudying.domain.srs.NewWordOrder
import com.alcohol.englishstudying.domain.srs.SrsSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "english_studying_settings"
)

/**
 * 全部自定义配置的读写入口。
 *
 * 标量各占一个 key；卡片字段配置是有序列表，序列化成一段 JSON 存成单键
 * —— DataStore Preferences 没有列表类型，拆成多个 key 反而会丢掉顺序。
 */
class SettingsRepository(private val context: Context) {

    val srsSettings: Flow<SrsSettings> = context.dataStore.data.map { prefs ->
        val defaults = SrsSettings.DEFAULT
        SrsSettings(
            dailyNewLimit = prefs[Keys.DAILY_NEW_LIMIT] ?: defaults.dailyNewLimit,
            dailyReviewLimit = prefs[Keys.DAILY_REVIEW_LIMIT] ?: defaults.dailyReviewLimit,
            newWordOrder = prefs[Keys.NEW_WORD_ORDER]
                ?.let { name -> NewWordOrder.entries.firstOrNull { it.name == name } }
                ?: defaults.newWordOrder,
            learningStepsMinutes = prefs[Keys.LEARNING_STEPS]
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: defaults.learningStepsMinutes,
            graduatingIntervalDays = prefs[Keys.GRADUATING_INTERVAL] ?: defaults.graduatingIntervalDays,
            easyIntervalDays = prefs[Keys.EASY_INTERVAL] ?: defaults.easyIntervalDays,
            startingEase = prefs[Keys.STARTING_EASE] ?: defaults.startingEase,
            easyBonus = prefs[Keys.EASY_BONUS] ?: defaults.easyBonus,
            hardMultiplier = prefs[Keys.HARD_MULTIPLIER] ?: defaults.hardMultiplier,
            intervalModifier = prefs[Keys.INTERVAL_MODIFIER] ?: defaults.intervalModifier,
            minIntervalDays = prefs[Keys.MIN_INTERVAL] ?: defaults.minIntervalDays,
            maxIntervalDays = prefs[Keys.MAX_INTERVAL] ?: defaults.maxIntervalDays,
            leechThreshold = prefs[Keys.LEECH_THRESHOLD] ?: defaults.leechThreshold,
            studyDeckId = prefs[Keys.STUDY_DECK_ID]?.takeIf { it != NO_DECK }
        ).sanitized()
    }

    val cardFields: Flow<List<CardFieldConfig>> = context.dataStore.data.map { prefs ->
        decodeCardFields(prefs[Keys.CARD_FIELDS])
    }

    /** 学习页是否在作答前隐藏释义等提示类字段。 */
    val hideHintsUntilRevealed: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HIDE_HINTS] ?: true
    }

    suspend fun setDailyNewLimit(value: Int) = putInt(Keys.DAILY_NEW_LIMIT, value)
    suspend fun setDailyReviewLimit(value: Int) = putInt(Keys.DAILY_REVIEW_LIMIT, value)
    suspend fun setNewWordOrder(value: NewWordOrder) = putString(Keys.NEW_WORD_ORDER, value.name)
    suspend fun setLearningSteps(steps: List<Int>) =
        putString(Keys.LEARNING_STEPS, steps.joinToString(","))

    suspend fun setGraduatingInterval(value: Double) = putDouble(Keys.GRADUATING_INTERVAL, value)
    suspend fun setEasyInterval(value: Double) = putDouble(Keys.EASY_INTERVAL, value)
    suspend fun setStartingEase(value: Double) = putDouble(Keys.STARTING_EASE, value)
    suspend fun setEasyBonus(value: Double) = putDouble(Keys.EASY_BONUS, value)
    suspend fun setHardMultiplier(value: Double) = putDouble(Keys.HARD_MULTIPLIER, value)
    suspend fun setIntervalModifier(value: Double) = putDouble(Keys.INTERVAL_MODIFIER, value)
    suspend fun setMinInterval(value: Double) = putDouble(Keys.MIN_INTERVAL, value)
    suspend fun setMaxInterval(value: Double) = putDouble(Keys.MAX_INTERVAL, value)
    suspend fun setLeechThreshold(value: Int) = putInt(Keys.LEECH_THRESHOLD, value)

    /** null 表示学习全部词库。 */
    suspend fun setStudyDeckId(value: Long?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(Keys.STUDY_DECK_ID)
            else prefs[Keys.STUDY_DECK_ID] = value
        }
    }

    suspend fun setCardFields(config: List<CardFieldConfig>) =
        putString(Keys.CARD_FIELDS, encodeCardFields(config))

    suspend fun setHideHintsUntilRevealed(value: Boolean) = putBoolean(Keys.HIDE_HINTS, value)

    /** 只重置算法参数，不动词库与学习进度。 */
    suspend fun resetSrsSettings() {
        context.dataStore.edit { prefs ->
            Keys.srsKeys.forEach { prefs.remove(it) }
        }
    }

    // ---- 内部工具 ----

    private suspend fun putInt(key: Preferences.Key<Int>, value: Int) =
        context.dataStore.edit { it[key] = value }

    private suspend fun putBoolean(key: Preferences.Key<Boolean>, value: Boolean) =
        context.dataStore.edit { it[key] = value }

    private suspend fun putDouble(key: Preferences.Key<Double>, value: Double) =
        context.dataStore.edit { it[key] = value }

    private suspend fun putString(key: Preferences.Key<String>, value: String) =
        context.dataStore.edit { it[key] = value }

    private object Keys {
        val DAILY_NEW_LIMIT = intPreferencesKey("daily_new_limit")
        val DAILY_REVIEW_LIMIT = intPreferencesKey("daily_review_limit")
        val NEW_WORD_ORDER = stringPreferencesKey("new_word_order")
        val LEARNING_STEPS = stringPreferencesKey("learning_steps")
        val GRADUATING_INTERVAL = doublePreferencesKey("graduating_interval")
        val EASY_INTERVAL = doublePreferencesKey("easy_interval")
        val STARTING_EASE = doublePreferencesKey("starting_ease")
        val EASY_BONUS = doublePreferencesKey("easy_bonus")
        val HARD_MULTIPLIER = doublePreferencesKey("hard_multiplier")
        val INTERVAL_MODIFIER = doublePreferencesKey("interval_modifier")
        val MIN_INTERVAL = doublePreferencesKey("min_interval")
        val MAX_INTERVAL = doublePreferencesKey("max_interval")
        val LEECH_THRESHOLD = intPreferencesKey("leech_threshold")
        val STUDY_DECK_ID = longPreferencesKey("study_deck_id")
        val CARD_FIELDS = stringPreferencesKey("card_fields")
        val HIDE_HINTS = booleanPreferencesKey("hide_hints")

        val srsKeys = listOf(
            DAILY_NEW_LIMIT, DAILY_REVIEW_LIMIT, NEW_WORD_ORDER, LEARNING_STEPS,
            GRADUATING_INTERVAL, EASY_INTERVAL, STARTING_EASE, EASY_BONUS,
            HARD_MULTIPLIER, INTERVAL_MODIFIER, MIN_INTERVAL, MAX_INTERVAL,
            LEECH_THRESHOLD
        )
    }

    companion object {
        /** study_deck_id 缺席即「全部词库」，这个哨兵值只用于防御脏数据。 */
        const val NO_DECK = -1L

        internal fun encodeCardFields(config: List<CardFieldConfig>): String {
            val array = JSONArray()
            config.forEach { item ->
                array.put(
                    JSONObject().apply {
                        put("key", item.field.name)
                        put("enabled", item.enabled)
                        put("expanded", item.expanded)
                    }
                )
            }
            return array.toString()
        }

        internal fun decodeCardFields(raw: String?): List<CardFieldConfig> {
            if (raw.isNullOrBlank()) return CardFieldConfigDefaults.default()

            val parsed = runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val field = CardField.fromNameOrNull(obj.optString("key")) ?: return@mapNotNull null
                    CardFieldConfig(
                        field = field,
                        enabled = obj.optBoolean("enabled", true),
                        expanded = obj.optBoolean("expanded", field.defaultExpanded)
                    )
                }
            }.getOrElse { return CardFieldConfigDefaults.default() }

            val seen = parsed.map { it.field }.toSet()
            // 补上本次解析没覆盖到的字段。这样以后往 CardField 里加新字段时，
            // 老用户存下的 JSON 不会导致新字段永远不显示。
            // 注意要 map 成 CardFieldConfig —— List<CardFieldConfig> + List<CardField>
            // 没有公共超类型，直接相加是编译错误。
            val missing = CardField.entries
                .filterNot { it in seen }
                .map { CardFieldConfig(it, enabled = true, expanded = it.defaultExpanded) }
            return parsed.distinctBy { it.field } + missing
        }
    }
}
