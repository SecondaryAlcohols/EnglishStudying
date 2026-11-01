package com.alcohol.englishstudying.data.local

import androidx.room.Embedded
import com.alcohol.englishstudying.domain.srs.CardState

/**
 * 词库列表的一行：词条本体 + 它的记忆状态。
 *
 * 用 LEFT JOIN 而不是 @Relation，因为列表要按状态筛选、按到期时间排序，
 * 这些都必须在 SQL 里做，否则分页和计数会错。
 * 没有 review_states 行 = 从未学过，[state] 会是 null，展示时按 NEW 处理。
 */
data class WordListRow(
    @Embedded val word: WordEntity,
    val stateName: CardState?,
    val dueAtMinutes: Long?,
    val intervalDays: Double?,
    val lapses: Int?,
    /** 用户标签，逗号分隔（`GROUP_CONCAT` 拼出来的）；没有标签时为 null。 */
    val userTags: String? = null
) {
    val state: CardState get() = stateName ?: CardState.NEW

    /** 用户标签列表 —— 和 `word.tagList`（系统标签）是两回事。 */
    val userTagList: List<String>
        get() = userTags.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 展示用的状态：**「重学中」按积压算，不看 `state` 列。**
     *
     * `RELEARNING` 只在「忘记」到「下次作答」之间短暂存在 —— 一作答就被写成 `LEARNING`，
     * 而那一刻这个词**仍然没有重学完**。所以光看 `state` 列，正在重学的词会顶着「学习中」
     * 的徽章，词库按「重学中」筛更是一条都筛不出来（真机上就是这个现象）。
     *
     * 判据和 `上限+N` / `需巩固` 用同一个：`lapses > 0`（在复习阶段遗忘过）且仍在学习步内。
     * 新词在学习步里按「忘记」**不**累加 `lapses`，所以不会被误算成重学。
     *
     * 注意「重学中」是「学习中」的子集 —— 按「学习中」筛也会把这些词列出来，这是对的，
     * 它们确实还在学习步里。
     */
    val displayState: CardState
        get() = if (lapses != null && lapses > 0 &&
            (state == CardState.LEARNING || state == CardState.RELEARNING)
        ) {
            CardState.RELEARNING
        } else {
            state
        }
}
