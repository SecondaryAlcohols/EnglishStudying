package com.alcohol.englishstudying.data.repo

import androidx.room.withTransaction
import com.alcohol.englishstudying.data.local.AppDatabase
import com.alcohol.englishstudying.data.local.ReviewLogEntity
import com.alcohol.englishstudying.data.local.ReviewStateEntity
import com.alcohol.englishstudying.data.local.WordEntity
import com.alcohol.englishstudying.data.prefs.SettingsRepository
import com.alcohol.englishstudying.domain.model.WordRelationType
import com.alcohol.englishstudying.domain.relations.ChainGrouper
import com.alcohol.englishstudying.domain.srs.CardState
import com.alcohol.englishstudying.domain.srs.DailyPlanBuilder
import com.alcohol.englishstudying.domain.srs.NewWordMixer
import com.alcohol.englishstudying.domain.srs.NewWordOrder
import com.alcohol.englishstudying.domain.srs.PracticeSessionBuilder
import com.alcohol.englishstudying.domain.srs.Rating
import com.alcohol.englishstudying.domain.srs.ReviewSchedule
import com.alcohol.englishstudying.domain.srs.Sm2Scheduler
import com.alcohol.englishstudying.domain.srs.SrsSettings
import com.alcohol.englishstudying.domain.srs.SrsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 学习队列里的一张卡：词条本体 + 它当前的记忆状态。 */
data class StudyCard(
    val word: WordEntity,
    val state: SrsState
)

data class DailyProgress(
    val dueCount: Int,
    val newCount: Int,
    val answeredToday: Int,
    val elapsedTodayMs: Long,
    val learnedToday: Int = 0,
    val reviewTableCount: Int = 0,
    val todayDueReviewCount: Int = 0,
    /**
     * 今天「开始学」的词数（初学）：今天新学的 ∪ 今天开始重学的。
     * 恒有 [masteredToday] <= [startedToday]。
     */
    val startedToday: Int = 0,
    /** 上面那些里今天已经学完的（掌握），含今天重学完的。 */
    val masteredToday: Int = 0,
    /**
     * 还压在重学里的词数，界面上显示成 `上限 50+N`。
     *
     * **既不是「今天新产生的」，也不是现算的**：它是上一次复习结束那一刻冻结下来的
     * 积压值，存在 DataStore 里，所以能跨天稳定地摆着，直到下次复习结束才更新。
     * 它**不占新词额度** —— `dailyNewLimit` 算的始终是新词，这是贴在旁边额外显示的一项。
     */
    val relearnPending: Int = 0,
    val coolingCount: Int = 0,
    val earliestCoolingDueMinutes: Long? = null,
    val currentNowMinutes: Long = 0L,
    val learningCount: Int = 0
) {
    val reviewDoneToday: Int get() = (answeredToday - learnedToday).coerceAtLeast(0)

    val coolingWaitMinutes: Int get() {
        val earliest = earliestCoolingDueMinutes ?: return 0
        return (earliest - currentNowMinutes).coerceAtLeast(1L).toInt()
    }
}

data class PracticeSessionResult(
    val cards: List<StudyCard>,
    val updatedPracticedIds: Set<Long>,
    val resetCycle: Boolean,
    val poolTotal: Int = 0,
    val basePracticedCount: Int = 0
)

class StudyRepository(
    private val db: AppDatabase,
    /**
     * 只为了那个重学积压快照 —— 它存在 DataStore 里而不是 Room 里（见
     * [SettingsRepository.relearnBacklog] 的说明：不值得为显示一个数动 schema 迁移）。
     *
     * 名字**不能叫 `settings`**：这个类好几个方法都有 `settings: SrsSettings` 参数，
     * 那个参数会把构造属性遮蔽掉，`settings.relearnBacklog` 就编译不过了。
     */
    private val settingsStore: SettingsRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val currentTime: () -> LocalTime = { LocalTime.now() }
) {

    /**
     * 组装今日队列：到期复习卡在前，新词在后。
     *
     * 新词不预建 review_states 行 —— 只有第一次作答时才建。
     * 否则新词会被到期查询当成「到期卡」捞出来，每日限额就形同虚设。
     *
     * [groupRelated] 开启时，队列里互为变形的词（同一组变形关系）会被稳定重排到一起，
     * 见 [com.alcohol.englishstudying.domain.relations.ChainGrouper]。
     */
    suspend fun buildSession(
        settings: SrsSettings,
        groupRelated: Boolean = false,
        extraNewWords: Boolean = false,
        includeCooling: Boolean = false
    ): List<StudyCard> {
        val todayEpochDay = today().toEpochDay()
        val nowMinutes = nowMinutes(todayEpochDay)
        val deckId = settings.studyDeckId

        // 复习卡与学习卡各拿一份独立限额，两条查询分开跑。共用一条查询、一个 LIMIT 时，
        // 逾期复习卡撞满上限会把当天已经到期的学习卡整段截掉（排序恒定复习在前），
        // 而那些词已有 review_states 行、不再算新词，两条路都进不去。
        val reviewCards = toCards(
            db.reviewDao().dueReviewStates(nowMinutes, deckId, settings.dailyReviewLimit)
        )
        val learningCards = toCards(
            if (includeCooling) {
                db.reviewDao().allLearningStates(deckId, settings.dailyReviewLimit)
            } else {
                db.reviewDao().dueLearningStates(nowMinutes, deckId, settings.dailyReviewLimit)
            }
        )

        // 算今日新词额度：
        // 常规模式下：每日上限 - 今日已学。若已学满，不再抽取新词（只排到期复习与学习卡）。
        // 超额模式（extraNewWords=true）：用户主动要求「再背一组新词」，按一组规格抽取。
        val newLimit = if (extraNewWords) {
            settings.dailyNewLimit.coerceAtLeast(settings.groupSize)
        } else {
            val learnedToday = learnedTodayCount(deckId)
            (settings.dailyNewLimit - learnedToday).coerceAtLeast(0)
        }

        val newWords = if (newLimit <= 0) {
            emptyList()
        } else {
            when (settings.newWordOrder) {
                NewWordOrder.FREQUENCY -> db.wordDao().newWordsByFrequency(newLimit, deckId)
                NewWordOrder.SEQUENCE -> db.wordDao().newWordsBySequence(newLimit, deckId)
                NewWordOrder.RANDOM -> db.wordDao().newWordsByRandom(newLimit, deckId)
                NewWordOrder.MIXED -> {
                    // 池子必须是范围内「全部」未学词：mixer 按位置三等分出易/中/难，
                    // 只喂头部一小段（重要度最高的几十个）时三档全是易词，
                    // 用户配的「难」名额实际上拿不到真正低重要度的词，混合名存实亡。
                    // 这里只拉 id+重要度的轻量行，选完再按 id 取完整词条。
                    val pool = db.wordDao().unlearnedFrequencies(deckId)
                    val picked = NewWordMixer.mix(pool, newLimit, settings.newWordMix)
                    wordsInOrder(picked.map { it.id })
                }

                NewWordOrder.DECK_WEIGHTED -> {
                    val deckIds = settings.weightedDeckIds
                    if (deckIds.isEmpty()) {
                        // 一个词表都没选 = 不限制词表，等价于「按重要度」
                        // （见 SrsSettings.weightedDeckIds 的说明）
                        db.wordDao().newWordsByFrequency(newLimit, deckId)
                    } else {
                        // 每个选中的词表各拉一份「全部」未学词（轻量的 id+重要度行）——
                        // 权重就是各自的词数，只喂头部一小段会让权重变成那个截断值。
                        // 一个词可能同时属于多个词表，用 seen 去重、先到先得；
                        // 桶的顺序由 deckIds 决定，所以同样的配置每次给出的队列一致。
                        val seen = HashSet<Long>()
                        val buckets = deckIds.mapNotNull { id ->
                            db.wordDao().unlearnedFrequencies(id)
                                .filter { seen.add(it.id) }
                                .takeIf { it.isNotEmpty() }
                        }
                        val picked = NewWordMixer.mixByDeckSizes(buckets, newLimit)
                        wordsInOrder(picked.map { it.id })
                    }
                }
            }
        }
        val newCards = newWords.map {
            StudyCard(it, SrsState.newCard(todayEpochDay, settings.startingEase))
        }

        val planSettings = if (extraNewWords && newLimit > settings.dailyNewLimit) {
            settings.copy(dailyNewLimit = newLimit)
        } else {
            settings
        }
        val queue = DailyPlanBuilder.build(reviewCards, learningCards, newCards, planSettings)
        return if (groupRelated) groupFormChains(queue) else queue
    }

    /**
     * 单纯复习抽词（不修改任何 SRS 属性，纯内存练习）。
     *
     * 抽词规则（剔除已练完的、练完一轮自动重置、打乱顺序）全在
     * [PracticeSessionBuilder.plan] 里，这里只负责查候选池、把 id 换成词条。
     *
     * ⚠️ 候选池是 `review_states` 里 state 为 LEARNING / REVIEW 的**全表**
     * （[com.alcohol.englishstudying.data.local.ReviewDao.allReviewStates]），
     * **不以到期时间为门槛** —— 所以练习会抽到几十天后才到期的词，而首页「单纯复习
     * （待复习 N 词）」里的 N 也是这张全表的词数，和同一屏上按到期日算的「待复习」
     * 计数不是一个口径。
     */
    suspend fun buildPracticeSession(
        limit: Int = 20,
        practicedWordIds: Set<Long> = emptySet(),
        deckId: Long? = null,
        groupRelated: Boolean = false
    ): PracticeSessionResult {
        val allDue = db.reviewDao().allReviewStates(deckId)
        val plan = PracticeSessionBuilder.plan(
            dueIds = allDue.map { it.wordId },
            practicedIds = practicedWordIds,
            limit = limit
        )

        // 按 plan 给出的顺序回填完整词条 —— getByIds 不保证返回顺序，
        // 乱序会毁掉 plan 里那次打乱（那正是「不重复抽到同一批」的依据）
        val byId = allDue.associateBy { it.wordId }
        val rawCards = toCards(plan.pickedIds.mapNotNull { byId[it] })
        val cards = if (groupRelated) groupFormChains(rawCards) else rawCards

        return PracticeSessionResult(
            cards = cards,
            updatedPracticedIds = plan.practicedIds,
            resetCycle = plan.resetCycle,
            poolTotal = plan.poolTotal,
            basePracticedCount = plan.basePracticedCount
        )
    }

    /**
     * 按 id 批量取词条，**分块**发查询。
     *
     * `id IN (:ids)` 每行绑一个变量，而队列长度由「每日复习上限」决定（允许填到 5000），
     * 超过 999 就撞上 SQLite 的变量上限（Android 11 及更早）。分块在这里是安全的：
     * 这是纯 id 查找，块与块之间没有必须成对出现的行 —— 对比 [groupFormChains] 那种
     * 边查询，分块会把跨块的两端整条漏掉，所以那边改用全量取回 + 内存过滤。
     */
    private suspend fun wordsByIds(ids: List<Long>): List<WordEntity> =
        ids.chunked(AppDatabase.SQLITE_BIND_LIMIT).flatMap { db.wordDao().getByIds(it) }

    /** 按 mixer 给出的顺序把轻量行换成完整词条 —— `getByIds` 不保证返回顺序。 */
    private suspend fun wordsInOrder(ids: List<Long>): List<WordEntity> {
        if (ids.isEmpty()) return emptyList()
        val byId = wordsByIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /**
     * 把 review_states 的行转成队列里的卡。
     *
     * 按 [states] 的顺序取词 —— `getByIds` 不保证返回顺序，必须按原列表回填，
     * 否则「最早到期的排最前」这条排序会在这一步被打乱。
     */
    private suspend fun toCards(states: List<ReviewStateEntity>): List<StudyCard> {
        if (states.isEmpty()) return emptyList()
        val wordsById = wordsByIds(states.map { it.wordId }).associateBy { it.id }
        return states.mapNotNull { state ->
            wordsById[state.wordId]?.let { StudyCard(it, state.toDomain()) }
        }
    }

    /** 同一组变形的词排到一起；关系只看已经进队列的词，没进队列的成员不参与。 */
    private suspend fun groupFormChains(queue: List<StudyCard>): List<StudyCard> {
        if (queue.size < 2) return queue
        // 过滤放在内存里做，不用 SQL 的两个 IN —— 理由见 WordRelationDao.pairsOfType
        val ids = queue.mapTo(HashSet(queue.size)) { it.word.id }
        val pairs = db.relationDao().pairsOfType(WordRelationType.FORM)
            .filter { it.fromWordId in ids && it.toWordId in ids }
            .map { it.fromWordId to it.toWordId }
        return ChainGrouper.group(queue, keyOf = { it.word.id }, pairs = pairs)
    }

    /** 评分并落库。返回评分后的新状态，供界面直接展示「下次复习」。 */
    suspend fun submit(
        card: StudyCard,
        rating: Rating,
        elapsedMs: Long,
        settings: SrsSettings
    ): SrsState {
        // 太简单 / 太难：不走 SM-2，直接移出学习队列，词库里可移出名单恢复
        if (rating == Rating.EASY) return exclude(card, CardState.TOO_EASY)

        val todayEpochDay = today().toEpochDay()
        val nowMinutes = nowMinutes(todayEpochDay)
        val millis = now()

        val next = Sm2Scheduler(settings).review(
            current = card.state,
            rating = rating,
            nowMinutes = nowMinutes,
            todayEpochDay = todayEpochDay,
            nowMillis = millis
        )

        db.withTransaction {
            db.reviewDao().upsert(ReviewStateEntity.fromDomain(card.word.id, next))
            db.reviewDao().insertLog(
                ReviewLogEntity(
                    wordId = card.word.id,
                    reviewedAt = millis,
                    rating = rating,
                    elapsedMs = elapsedMs,
                    intervalDaysAfter = next.intervalDays
                )
            )
        }
        return next
    }

    /**
     * 把词移出学习：标记为太简单（[CardState.TOO_EASY]）或太难（[CardState.TOO_HARD]）。
     *
     * 记忆状态原样保留，只是状态位换掉 —— 词库里「移出」名单后能接着原进度继续。
     * 不写 review_logs：这不是一次作答，统计页的答题数不该被它污染。
     */
    suspend fun exclude(card: StudyCard, target: CardState): SrsState {
        require(target == CardState.TOO_EASY || target == CardState.TOO_HARD)
        val millis = now()
        val excluded = card.state.copy(state = target, lastReviewedAt = millis)
        db.reviewDao().upsert(ReviewStateEntity.fromDomain(card.word.id, excluded))
        return excluded
    }

    /**
     * 撤销刚做的 [exclude]，把词恢复成标记前的样子。
     *
     * 必须分两路，因为新词作答前**根本没有 review_states 行** —— 那一行是 [exclude] 凭空建的：
     *  - 未学过（[CardState.NEW]）→ **删行**。如果这里只是写回一个 state=NEW 的行，这个词会
     *    既进不了到期查询（NEW 不在白名单里），也不再满足 `id NOT IN (SELECT wordId FROM review_states)`，
     *    于是从新词队列里彻底消失 —— 正是 [buildSession] 注释里强调的那条前提。
     *  - 其余 → 把标记前那份状态整行写回，复习进度原样回来。
     *
     * 与 [exclude] 对称，同样不碰 review_logs（标记移出不是一次作答）。
     */
    suspend fun undoExclude(card: StudyCard) {
        if (card.state.state == CardState.NEW) {
            db.reviewDao().resetWord(card.word.id)
        } else {
            db.reviewDao().upsert(ReviewStateEntity.fromDomain(card.word.id, card.state))
        }
    }

    /**
     * 手动把某个词的下次复习排到 [days] 天后（0 = 今天）。
     *
     * **只对学过的词生效** —— 查不到 review_states 行就直接返回。给它建行等于把这个词
     * 从新词队列里抹掉（`newWordsBy*` 靠 `id NOT IN (SELECT wordId ...)` 找新词），
     * 那是 [undoExclude] 里刚强调过的同一个陷阱，不该从这条路径被绕过。
     *
     * 到期时刻落在当天零点，与 [Sm2Scheduler] 的天级间隔取整方式一致，
     * 手动排的卡和自动排的卡在 `ORDER BY dueAtMinutes` 里才可比。
     *
     * 顺带置成 [CardState.REVIEW]：学习步内的卡被显式排到 N 天后，语义上已经离开学习阶段了。
     * 不写 review_logs —— 这不是一次作答，统计页的答题数不该被它污染。
     *
     * **必须同时把 [ReviewStateEntity.repetitions] 抬到至少 1。** [Sm2Scheduler] 拿
     * `repetitions == 0` 当「这张卡从没排过」的判据，那时它会把 `intervalDays` 整个丢掉、
     * 重取毕业间隔。刚答过一次「认识」的新词正好是 `repetitions = 0`，于是手动设的
     * 「30 天后复习」在下次答「认识」时会变成「明天复习」—— 用户设的间隔被静默作废。
     */
    suspend fun setNextReview(wordId: Long, days: Int) {
        val row = db.reviewDao().get(wordId) ?: return
        val todayEpochDay = today().toEpochDay()
        db.reviewDao().upsert(
            row.copy(
                intervalDays = days.coerceAtLeast(0).toDouble(),
                dueAtMinutes = ReviewSchedule.dueAtMinutesFor(todayEpochDay, days),
                state = CardState.REVIEW,
                learningStep = 0,
                repetitions = maxOf(row.repetitions, 1)
            )
        )
    }

    /**
     * 把移出学习的词拉回来（太简单 / 太难名单 → 恢复学习）。分三种情况：
     *
     *  1. **一次都没作答过**（比如新词当天就标了太简单）→ 删行回到新词，从零开始；
     *  2. **学过、但被遗忘了** → 回到 [CardState.RELEARNING] 重学；
     *  3. **还有间隔在** → 回 `REVIEW` 接着原进度。
     *
     * 第 2 条是这里最容易写错的地方。遗忘会把 `repetitions` 和 `intervalDays` 一起清零
     * （见 [Sm2Scheduler] 的 AGAIN 分支），而这两个数曾经正是「有没有学过」的判据 ——
     * 于是一个学了很久的词走「遗忘 → 标太难 → 恢复」这一圈之后会被当成全新词把状态行删掉，
     * 历史 ease / lapses 全丢，还要重新占一次新词额度。所以判据换成**有没有作答日志**，
     * 分支逻辑单独抽成 [resumeActionFor]（纯函数，好单测）。
     */
    suspend fun resumeExcluded(wordId: Long) {
        val row = db.reviewDao().get(wordId) ?: return
        if (row.state != CardState.TOO_EASY && row.state != CardState.TOO_HARD) return

        val hasLogs = db.reviewDao().hasLogs(wordId)
        when (resumeActionFor(hasLogs, row.repetitions, row.intervalDays)) {
            ResumeAction.RESET_TO_NEW -> db.reviewDao().resetWord(wordId)

            ResumeAction.RELEARN -> db.reviewDao().upsert(
                row.copy(
                    state = CardState.RELEARNING,
                    learningStep = 0,
                    // 立即可学：恢复是用户的显式动作，不该再让他等一个学习步。
                    // 也正因为不套学习步偏移，这里不需要 SrsSettings 参数。
                    dueAtMinutes = nowMinutes(today().toEpochDay())
                )
            )

            ResumeAction.RESUME_REVIEW -> db.reviewDao().unsuspend(wordId)
        }
    }

    /**
     * 冻结一次重学积压快照 —— 在**复习结束时**调用一次。
     *
     * 取的是那一刻还压在重学里的词数，存进 DataStore 后界面就一直读它。所以这个数不会因为
     * 你随后重学完几个词而往下掉，也不会在半夜归零；它只在下次复习结束时更新。
     *
     * 按 [deckId] 过滤，是为了让它和旁边的初学 / 掌握 / 上限处在同一个学习范围。代价是
     * 中途换学习词表的话，这个冻结值会停留在旧范围上，直到下次复习结束。
     */
    suspend fun snapshotRelearnBacklog(deckId: Long?) {
        settingsStore.setRelearnBacklog(
            count = db.reviewDao().countPendingRelearn(deckId),
            epochDay = today().toEpochDay()
        )
    }

    /**
     * 快照不是今天写的（或压根没写过）就补算一次。每天首次进首页时调一次。
     *
     * 补这一步是因为「只在复习结束刷新」有个缺口：某天没把队列走完，那个数就会一直停在
     * 旧值，跨天也不动 —— 而它旁边的初学 / 掌握每天零点就归零了，两排数字会互相矛盾。
     * 每天首次进来刷一次之后，当天之内仍然只在复习结束时更新，冻结语义不变。
     */
    suspend fun refreshRelearnBacklogIfStale(deckId: Long?) {
        if (settingsStore.relearnBacklogDay.first() != today().toEpochDay()) {
            snapshotRelearnBacklog(deckId)
        }
    }

    /**
     * 今日进度。
     *
     * 注意 [dueCount] 里的「现在」是在订阅时算好并固定的，不会随时间推进自动刷新。
     * 对一次学习会话来说够用 —— 进入学习页时重新订阅即可拿到新值。
     */
    fun observeProgress(settings: SrsSettings): Flow<DailyProgress> {
        val todayEpochDay = today().toEpochDay()
        val nowMinutes = nowMinutes(todayEpochDay)
        val startMillis = today().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMillis = startMillis + MILLIS_PER_DAY
        val endOfDayMinutes = (todayEpochDay + 1) * SrsState.MINUTES_PER_DAY
        val deckId = settings.studyDeckId

        val baseProgress = combine(
            db.reviewDao().observeDueCount(nowMinutes, deckId),
            db.wordDao().observeNewCount(deckId),
            db.reviewDao().observeReviewedCount(startMillis, endMillis, deckId),
            db.reviewDao().observeElapsedMs(startMillis, endMillis),
            db.reviewDao().observeLearnedCount(startMillis, endMillis, deckId)
        ) { due, new, answered, elapsed, learned ->
            DailyProgress(
                dueCount = due,
                newCount = new,
                answeredToday = answered,
                elapsedTodayMs = elapsed,
                learnedToday = learned
            )
        }

        val intermediate = combine(
            baseProgress,
            db.reviewDao().observeReviewCount(deckId),
            db.reviewDao().observeTodayDueReviewCount(startMillis, endMillis, endOfDayMinutes, deckId)
        ) { p, reviewTableCount, todayDueReviewCount ->
            p.copy(
                reviewTableCount = reviewTableCount,
                todayDueReviewCount = todayDueReviewCount
            )
        }

        val intermediate2 = combine(
            intermediate,
            db.reviewDao().observeStartedTodayCount(startMillis, endMillis, deckId),
            db.reviewDao().observeMasteredTodayCount(startMillis, endMillis, deckId),
            // 积压值不走 Room 查询：它是一份冻结的快照，读的是 DataStore 里存下的那次结果
            settingsStore.relearnBacklog
        ) { p, started, mastered, relearnPending ->
            p.copy(
                startedToday = started,
                masteredToday = mastered,
                relearnPending = relearnPending
            )
        }

        return combine(
            intermediate2,
            db.reviewDao().observeCoolingCount(nowMinutes, deckId),
            db.reviewDao().observeEarliestCoolingDueMinutes(nowMinutes, deckId),
            db.reviewDao().observeLearningCount(deckId)
        ) { p, coolingCount, earliestDue, learningCount ->
            p.copy(
                coolingCount = coolingCount,
                earliestCoolingDueMinutes = earliestDue,
                currentNowMinutes = nowMinutes,
                learningCount = learningCount
            )
        }
    }

    /**
     * 处于 LEARNING 状态（学习中/需巩固）的卡片总数。
     */
    fun observeLearningCount(deckId: Long? = null): Flow<Int> {
        return db.reviewDao().observeLearningCount(deckId)
    }

    /**
     * 单纯复习记录作答日志（仅记录耗时、评分与时刻，绝不修改 review_states 中的任何 SRS 调度属性）。
     */
    suspend fun logPracticeReview(
        wordId: Long,
        rating: Rating,
        elapsedMs: Long,
        currentIntervalDays: Double
    ) {
        db.reviewDao().insertLog(
            ReviewLogEntity(
                wordId = wordId,
                reviewedAt = now(),
                rating = rating,
                elapsedMs = elapsedMs,
                intervalDaysAfter = currentIntervalDays
            )
        )
    }

    /**
     * 待复习表中的词数（包含 LEARNING 与 REVIEW 状态，直接查表不等到期）。
     */
    fun observeReviewCount(deckId: Long? = null): Flow<Int> {
        return db.reviewDao().observeReviewCount(deckId)
    }

    /**
     * 今日新学词数：今天第一次作答的词有几个。
     *
     * 时间窗口的算法与 [observeProgress] 里那段保持一致（当天零点 → 次日零点）。
     */
    private fun todayStartMillis(): Long =
        today().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun observeLearnedTodayCount(deckId: Long? = null): Flow<Int> {
        val startMillis = todayStartMillis()
        return db.reviewDao().observeLearnedCount(startMillis, startMillis + MILLIS_PER_DAY, deckId)
    }

    suspend fun learnedTodayCount(deckId: Long? = null): Int {
        val startMillis = todayStartMillis()
        return db.reviewDao().learnedCount(startMillis, startMillis + MILLIS_PER_DAY, deckId)
    }

    /** 挂起的卡不在每日队列里，只能在词库里手动恢复。 */
    suspend fun unsuspend(wordId: Long) = db.reviewDao().unsuspend(wordId)

    suspend fun resetProgress(wordId: Long) = db.reviewDao().resetWord(wordId)

    suspend fun resetAllProgress() {
        db.withTransaction {
            db.reviewDao().resetAll()
            db.reviewDao().clearLogs()
        }
    }

    private fun nowMinutes(todayEpochDay: Long): Long =
        DailyPlanBuilder.nowMinutes(todayEpochDay, currentTime().hour, currentTime().minute)

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

/** 从「移出学习」名单恢复一个词时该做什么。 */
internal enum class ResumeAction {
    /** 一次都没作答过 —— 删掉状态行，当新词重新开始。 */
    RESET_TO_NEW,

    /** 学过、但被遗忘了 —— 回到 `RELEARNING` 重学，保留 ease / lapses 历史。 */
    RELEARN,

    /** 还有间隔在 —— 回 `REVIEW` 接着原进度。 */
    RESUME_REVIEW
}

/**
 * 从「移出学习」名单恢复时走哪条路。
 *
 * 判据是**有没有作答日志**，刻意不用 `repetitions` / `intervalDays` —— 遗忘会把这两个数
 * 一起清零（见 [Sm2Scheduler] 的 AGAIN 分支），拿它们当「有没有学过」的判据，就会把学过
 * 很久的词当成新词把状态行删掉，历史 ease / lapses 全丢，还要重新占一次新词额度。
 *
 * 抽成纯函数是因为这个判据判错的后果是**静默的数据丢失**，肉眼看不出来，必须能单测。
 */
internal fun resumeActionFor(
    hasLogs: Boolean,
    repetitions: Int,
    intervalDays: Double
): ResumeAction = when {
    !hasLogs -> ResumeAction.RESET_TO_NEW
    repetitions == 0 && intervalDays <= 0.0 -> ResumeAction.RELEARN
    else -> ResumeAction.RESUME_REVIEW
}
