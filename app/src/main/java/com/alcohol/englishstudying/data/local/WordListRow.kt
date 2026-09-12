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
    val lapses: Int?
) {
    val state: CardState get() = stateName ?: CardState.NEW
}
