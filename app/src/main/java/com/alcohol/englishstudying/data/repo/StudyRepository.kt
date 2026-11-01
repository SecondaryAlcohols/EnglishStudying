package com.alcohol.englishstudying.data.repo

import androidx.room.withTransaction
import com.alcohol.englishstudying.data.local.AppDatabase
import com.alcohol.englishstudying.data.local.ReviewLogEntity
import com.alcohol.englishstudying.data.local.ReviewStateEntity
import com.alcohol.englishstudying.data.local.TodayLogRow
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
     * 今天复习完成的**旧词**数（今天有作答、且首次作答不在今天）。
     *
     * 含今天在复习卡上点了「太简单 / 太难」移出学习的旧词 —— 那也是过了一遍卡、对记忆质量
     * 下过判断，只是结论是「别再考了」。但不含今天新学又被移出的词，它们算「初学」。
     *
     * 是**查出来的**，不是 `answeredToday - learnedToday`：那两个口径不是包含关系 ——
     * `learnedToday` 含「没作答就被标移出」的词（它们不写日志），减出来会把复习数多扣掉。
     * 详见 `ReviewDao.observeReviewDoneCount` 里记的那笔账。
     */
    val reviewDoneToday: Int = 0,
    /**
     * 今天已经「过过一遍」的旧卡数（任意评分，含移出学习）—— 已用的复习额度。
     *
     * 和 [reviewDoneToday] 只差一个评分过滤：忘记/模糊也算过卡，只是还没完成。
     * 每日复习上限数的是「发出去多少张复习卡」，不是「完成多少张」。
     */
    val reviewAttemptedToday: Int = 0,
    /** 今日复习上限还剩多少张；0 表示今天不再发复习卡（学习步里的卡不受影响）。 */
    val reviewBudgetRemaining: Int = 0,
    /**
     * 今日复习的目标数，已经把每日复习上限算进去：
     * `min(今天应复习总数, max(已完成, 已过卡) + 剩余额度)`。
     *
     * 上限用完后它就等于 max(已完成, 已过卡)，界面显示 X/X，不再拿全天到期数
     * （比如 326）画一个今天根本走不到的目标。
     */
    val reviewTargetToday: Int = 0,
    /**
     * 今天「开始学」的词数（初学）：今天新学的 ∪ 今天忘了又重新学起来的。
     *
     * 后一半**要等重新答对才算**：复习卡上点「忘记」只是掉进重学，那一刻不动初学，
     * 只让 `relearnPending` 的 +N 涨 1。等这张卡学回 `REVIEW` 之后才算学完（掌握）。
     * 恒有 [masteredToday] <= [startedToday]。
     */
    val startedToday: Int = 0,
    /**
     * 今天点过「模糊」、还没再答对的旧词数。
     *
     * 这批卡的排期在第一次模糊时就被定死到未来，既不在到期复习里、也不算已复习 ——
     * 它们悬在中间，卡片页顶部的进度条把它们画成浅绿（「复习中」）。
     */
    val hazyToday: Int = 0,
    /**
     * 上面那些里今天已经学完的（掌握），含今天重学回 `REVIEW` 的、也含今天标为「太简单」的新词 ——
     * 一眼就会的词本来就不必再学，它和「学了并答对」一样算学完。
     */
    val masteredToday: Int = 0,
    /**
     * 今天标为「太难」的**新词**数。它们不进初学也不进掌握，而是**直接从当天的上限里扣掉**
     * 这么多个名额：上限 20、扣掉 2 个就显示成 18。
     *
     * 界面靠它把「初学 1 / 掌握 1 / 上限 18」凑成一组自洽的数 —— 放弃的词既不装作学过，
     * 也不占着名额让用户找不到「为什么还剩 17 个却说今天已满」。
     * 口径见 `ReviewDao.observeTooHardNewCount`。
     */
    val tooHardNewToday: Int = 0,
    /**
     * 界面上 `上限 50+N` 里的那个 N = **上次复习结束冻结的积压 + 这次会话已经忘了几个**。
     *
     * 两段都存在 DataStore 里（见 `SettingsRepository`）：冻结的那段只在复习结束时按真实积压
     * 重算；会话那段复习中每忘一个 +1，复习结束时随新快照一起归零（同一个 edit，原子）。
     * 于是 N 在复习中一直往上走、看得见，复习一结束就落回真实值，而且**卡片页和学习首页
     * 读到的是同一个数**。
     *
     * 它**不占新词额度** —— `dailyNewLimit` 算的始终是新词，这是贴在旁边额外显示的一项。
     */
    val relearnPending: Int = 0,
    val coolingCount: Int = 0,
    val earliestCoolingDueMinutes: Long? = null,
    val currentNowMinutes: Long = 0L,
    val learningCount: Int = 0
) {
    val coolingWaitMinutes: Int get() {
        val earliest = earliestCoolingDueMinutes ?: return 0
        return (earliest - currentNowMinutes).coerceAtLeast(1L).toInt()
    }

    /**
     * 「待复习」：今天还发得出来的复习卡 = 到期的天级复习卡（受每日上限剩余额度截断）
     * + 已经到期的学习步卡。
     *
     * 学习步卡是在途的卡，不受复习上限影响；[coolingCount] 是它们里面还没到点的那部分，
     * 扣掉才是「现在就能做」的。学习首页和卡片页顶部的进度条都用这一个口径，
     * 免得两处各写一遍、慢慢走偏。
     */
    val regularDueToday: Int
        get() {
            val learningDue = (learningCount - coolingCount).coerceAtLeast(0)
            val reviewDue = (dueCount - learningDue).coerceAtLeast(0)
            return minOf(reviewDue, reviewBudgetRemaining) + learningDue
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
     * 但**只在同类卡内部**——新词不会被已学卡的同形词拉到复习流中间（见 [groupFormChains]），
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

        // 每日复习上限是「今天最多发多少张复习卡」，按已经过过一遍的旧卡数扣。
        // 不用 `dailyReviewLimit` 直接当 LIMIT —— 那是每次 buildSession 都重新给 200，
        // 用户开几个会话就能无限续杯。
        val dayStartMillis = todayStartMillis()
        val reviewAttempted = db.reviewDao().reviewAttemptedCount(
            dayStartMillis,
            dayStartMillis + MILLIS_PER_DAY,
            deckId
        )
        val reviewBudget = (settings.dailyReviewLimit - reviewAttempted).coerceAtLeast(0)

        // 复习卡与学习卡各拿一份独立限额，两条查询分开跑。共用一条查询、一个 LIMIT 时，
        // 逾期复习卡撞满上限会把当天已经到期的学习卡整段截掉（排序恒定复习在前），
        // 而那些词已有 review_states 行、不再算新词，两条路都进不去。
        // 复习卡吃的是「每日上限剩余额度」；学习卡是已经在途的卡，仍按 settings.dailyReviewLimit 取。
        //
        // 「今天模糊过、还没再答对」的旧卡单独捞一次并排在最前：它们的排期在第一次模糊时
        // 就定死到了未来，到期查询捞不到，会话一断就会当天消失（见 mergeOwed 的说明）。
        // 它们已经被算进过卡数，不再消耗上限额度，所以不受 reviewBudget 约束。
        val owedCards = toCards(
            db.reviewDao().hazilyReviewedToday(dayStartMillis, dayStartMillis + MILLIS_PER_DAY, deckId)
        )
        val reviewCards = DailyPlanBuilder.mergeOwed(
            owedCards,
            toCards(db.reviewDao().dueReviewStates(nowMinutes, deckId, reviewBudget)),
            keyOf = { it.word.id }
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

        // 「优先学」先抽：它压过用户选的所有顺序（包括按难度混合、按词表比例）。
        // 抽走的词当场清标记，下一次抽新词就不会再优先它们。
        val priorityWords = if (newLimit <= 0) {
            emptyList()
        } else {
            db.wordDao().priorityNewWords(newLimit, deckId)
        }
        val priorityIds = priorityWords.mapTo(HashSet()) { it.id }
        val normalLimit = (newLimit - priorityWords.size).coerceAtLeast(0)

        val normalWords = if (normalLimit <= 0) {
            emptyList()
        } else {
            when (settings.newWordOrder) {
                // 正常池按 newLimit 查（不是 normalLimit）：优先词可能就排在这批里，
                // 滤掉它们之后仍然剩得下 normalLimit 个，不会少抽。
                NewWordOrder.FREQUENCY -> db.wordDao().newWordsByFrequency(newLimit, deckId)
                    .filterNot { it.id in priorityIds }
                    .take(normalLimit)

                NewWordOrder.SEQUENCE -> db.wordDao().newWordsBySequence(newLimit, deckId)
                    .filterNot { it.id in priorityIds }
                    .take(normalLimit)

                NewWordOrder.RANDOM -> db.wordDao().newWordsByRandom(newLimit, deckId)
                    .filterNot { it.id in priorityIds }
                    .take(normalLimit)

                NewWordOrder.MIXED -> {
                    // 池子必须是范围内「全部」未学词：mixer 按位置三等分出易/中/难，
                    // 只喂头部一小段（排名最靠前的几十个）时三档全是易词，
                    // 用户配的「难」名额实际上拿不到真正生僻的词，混合名存实亡。
                    // 这里只拉 id+排名的轻量行，选完再按 id 取完整词条。
                    val pool = db.wordDao().unlearnedFrequencies(deckId)
                        .filterNot { it.id in priorityIds }
                    val picked = NewWordMixer.mix(pool, normalLimit, settings.newWordMix)
                    wordsInOrder(picked.map { it.id })
                }

                NewWordOrder.DECK_WEIGHTED -> {
                    val deckIds = settings.weightedDeckIds
                    if (deckIds.isEmpty()) {
                        // 一个词表都没选 = 不限制词表，等价于「按词频排名」
                        // （见 SrsSettings.weightedDeckIds 的说明）
                        db.wordDao().newWordsByFrequency(newLimit, deckId)
                            .filterNot { it.id in priorityIds }
                            .take(normalLimit)
                    } else {
                        // 每个选中的词表各拉一份「全部」未学词（轻量的 id+排名行）——
                        // 权重就是各自的词数，只喂头部一小段会让权重变成那个截断值。
                        // 一个词可能同时属于多个词表，用 seen 去重、先到先得；
                        // 桶的顺序由 deckIds 决定，所以同样的配置每次给出的队列一致。
                        val seen = HashSet<Long>()
                        val buckets = deckIds.mapNotNull { id ->
                            db.wordDao().unlearnedFrequencies(id)
                                .filterNot { it.id in priorityIds }
                                .filter { seen.add(it.id) }
                                .takeIf { it.isNotEmpty() }
                        }
                        val picked = NewWordMixer.mixByDeckSizes(buckets, normalLimit)
                        wordsInOrder(picked.map { it.id })
                    }
                }
            }
        }
        val newWords = priorityWords + normalWords
        val newCards = newWords.map {
            StudyCard(it, SrsState.newCard(todayEpochDay, settings.startingEase))
        }

        val planSettings = if (extraNewWords && newLimit > settings.dailyNewLimit) {
            settings.copy(dailyNewLimit = newLimit)
        } else {
            settings
        }
        val queue = DailyPlanBuilder.build(reviewCards, learningCards, newCards, planSettings)
        val result = if (groupRelated) groupFormChains(queue) else queue
        if (priorityWords.isNotEmpty()) {
            // 抽走即出「优先学」。放在建队之后：万一上面哪一步抛了，标记还留着。
            priorityWords.map { it.id }
                .chunked(AppDatabase.SQLITE_BIND_LIMIT)
                .forEach { db.wordDao().clearPriority(it) }
        }
        return result
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

    /**
     * 同一组变形的词排到一起；关系只看已经进队列的词，没进队列的成员不参与。
     *
     * 连排**只在同类卡内部**生效（[ChainGrouper.groupBySection]），新词绝不参与已学卡的
     * 连排：建队顺序是「复习卡 → 学习卡 → 新词」，而连排是按「组内第一个成员的位置」
     * 整组搬运的 —— 不分段时，队尾的新词会被复习卡里的同形词拉进复习流中间。
     * 2026-10-26 真机实测：`knows` 标完「太简单」，下一张就是新词 `know`；`leaves` 之后
     * 紧跟 `leave`。复习只走了 15 张就先学了 2 个新词，首页「初学 / 掌握」提前跳数，
     * 看着像复习没走完就进了学习。
     */
    private suspend fun groupFormChains(queue: List<StudyCard>): List<StudyCard> {
        if (queue.size < 2) return queue
        // 过滤放在内存里做，不用 SQL 的两个 IN —— 理由见 WordRelationDao.pairsOfType
        val ids = queue.mapTo(HashSet(queue.size)) { it.word.id }
        val pairs = db.relationDao().pairsOfType(WordRelationType.FORM)
            .filter { it.fromWordId in ids && it.toWordId in ids }
            .map { it.fromWordId to it.toWordId }
        return ChainGrouper.groupBySection(
            queue,
            keyOf = { it.word.id },
            pairs = pairs,
            // 0 = 已有 review_states 的卡（REVIEW / LEARNING / RELEARNING），1 = 新词
            sectionOf = { if (it.state.state == CardState.NEW) 1 else 0 }
        )
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
     * 但它**仍算一次「今日复习」**（旧词的话）：靠 [lastReviewedAt] 落回今天来认，
     * 见 `ReviewDao.observeReviewDoneCount` 第 2 条来源 —— 卡确实过了用户的眼睛。
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
     * 取那一刻还压在重学里的词数（含这次忘的、扣掉这次重学完的），存进 DataStore，同时把
     * 会话内的遗忘计数归零。所以冻结值只在复习结束时更新，会话计数只增不减地负责「复习中
     * 看得见」，两者相加就是界面上的 N。
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
     * 记一次「遗忘」：在复习卡上按了「忘记」，会话内的遗忘计数 +1。
     *
     * 只该在**复习阶段的忘记**上调用 —— `lapses` 也只在那时 +1（新词在学习步里按忘记不算），
     * 两边口径必须一致，否则会话中涨的数和复习结束时落回的值会对不上。
     */
    suspend fun recordForgottenWord() = settingsStore.bumpRelearnSessionLapses()

    /**
     * 只在**从来没写过**快照时补算一次（装完新版本、清了应用数据）。
     *
     * 刻意**不**按「不是今天写的」去重算。上学过一次当：2026-09-27 早上 10:01 打开 App 时
     * 按「跨天」重算了一次，而那一刻当天的复习根本没走完 —— 拿一个中间状态覆盖掉了上次
     * 复习结束留下的值，写进去一个假的 0，之后界面一直显示 `50` 而没有 `+N`。
     * 规则是「**复习结束时算一次**」：除了复习结束，任何时刻都不该重算。
     *
     * 只保留这一次引导是因为：没有它的话，一个从来不把队列走完的用户永远看不到这个数
     * （值永远是初始的 0）。写过之后这一支就再不走。
     */
    suspend fun bootstrapRelearnBacklogIfAbsent(deckId: Long?) {
        if (settingsStore.relearnBacklogDay.first() == null) {
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
            db.reviewDao().observeTodayDueReviewCount(startMillis, endMillis, endOfDayMinutes, deckId),
            // 「今日复习」的分子必须查，不能拿 answeredToday - learnedToday 去减（见字段说明）
            db.reviewDao().observeReviewDoneCount(startMillis, endMillis, deckId),
            // 今天标「太难」的新词：从当天新词上限里扣掉的名额
            db.reviewDao().observeTooHardNewCount(startMillis, endMillis, deckId)
        ) { p, reviewTableCount, todayDueReviewCount, reviewDoneToday, tooHardNew ->
            p.copy(
                reviewTableCount = reviewTableCount,
                todayDueReviewCount = todayDueReviewCount,
                reviewDoneToday = reviewDoneToday,
                tooHardNewToday = tooHardNew
            )
        }

        val intermediate2 = combine(
            intermediate,
            db.reviewDao().observeStartedTodayCount(startMillis, endMillis, deckId),
            db.reviewDao().observeMasteredTodayCount(startMillis, endMillis, deckId),
            // 积压值不走 Room：它是 DataStore 里存的两段（复习结束冻结的 + 会话中已忘的）
            settingsStore.relearnBacklog,
            settingsStore.relearnSessionLapses
        ) { p, started, mastered, frozenBacklog, sessionLapses ->
            p.copy(
                startedToday = started,
                masteredToday = mastered,
                relearnPending = frozenBacklog + sessionLapses
            )
        }

        // 单独一档：combine 的强类型重载最多 5 个流，上面那组已经满了
        val withHazy = combine(
            intermediate2,
            db.reviewDao().observeHazyCount(startMillis, endMillis, deckId)
        ) { p, hazy -> p.copy(hazyToday = hazy) }

        return combine(
            withHazy,
            db.reviewDao().observeCoolingCount(nowMinutes, deckId),
            db.reviewDao().observeEarliestCoolingDueMinutes(nowMinutes, deckId),
            db.reviewDao().observeLearningCount(deckId),
            db.reviewDao().observeReviewAttemptedCount(startMillis, endMillis, deckId)
        ) { p, coolingCount, earliestDue, learningCount, attempted ->
            // 每日复习上限按「已经过过多少张旧卡」扣，不是按「完成多少张」：
            // 忘记/模糊也算过卡。额度用完后 buildSession 不再发复习卡。
            val remaining = (settings.dailyReviewLimit - attempted).coerceAtLeast(0)
            val target = minOf(
                p.todayDueReviewCount,
                maxOf(p.reviewDoneToday, attempted) + remaining
            )
            p.copy(
                coolingCount = coolingCount,
                earliestCoolingDueMinutes = earliestDue,
                currentNowMinutes = nowMinutes,
                learningCount = learningCount,
                reviewAttemptedToday = attempted,
                reviewBudgetRemaining = remaining,
                reviewTargetToday = target
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
     * 只记一行作答日志，不动排期（不碰 review_states）。
     *
     * 两处会用到：单纯复习（本来就不改任何 SRS 属性）、以及复习循环里「排期已按第一次
     * 模糊定死」之后的作答 —— 重考也是作答，答题次数与用时得留下，但日期不许再变。
     * [currentIntervalDays] 记的是当前那个已经定死的间隔，不是这次评分会算出的间隔。
     */
    suspend fun logAnswer(
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

    /**
     * 今天点过模糊、还没再答对的旧词 —— 它们虽然排到了未来，今天的复习循环还没走完。
     *
     * 会话开始时拿它给 `StudyViewModel.lockedWordIds` 播种：这些卡的排期在第一次模糊时
     * 就定死了，重新进入会话后再点「认识」只能写日志、不能改日期，否则「排期按模糊算」
     * 这条规则会在退出应用之后失效。
     */
    suspend fun hazyWordIdsToday(deckId: Long? = null): Set<Long> {
        val startMillis = todayStartMillis()
        return db.reviewDao()
            .hazilyReviewedToday(startMillis, startMillis + MILLIS_PER_DAY, deckId)
            .mapTo(HashSet()) { it.wordId }
    }

    /** 今天每一次作答的流水（最新在上），学习页「查看日志」弹窗用。 */
    suspend fun todayLogs(limit: Int = 300): List<TodayLogRow> {
        val startMillis = todayStartMillis()
        return db.reviewDao().todayLogs(startMillis, startMillis + MILLIS_PER_DAY, limit)
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
