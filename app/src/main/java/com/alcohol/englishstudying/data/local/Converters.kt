package com.alcohol.englishstudying.data.local

import androidx.room.TypeConverter
import com.alcohol.englishstudying.domain.srs.CardState
import com.alcohol.englishstudying.domain.srs.Rating

/**
 * 枚举以名字存字符串。这样即使以后往枚举中间插入新值，也不会让已有数据错位
 * —— 存 ordinal 的话插入一个值就会把所有历史记录解释错。
 */
class Converters {

    @TypeConverter
    fun cardStateToString(value: CardState): String = value.name

    @TypeConverter
    fun stringToCardState(value: String?): CardState =
        CardState.fromNameOrNull(value) ?: CardState.NEW

    @TypeConverter
    fun ratingToString(value: Rating): String = value.name

    @TypeConverter
    fun stringToRating(value: String?): Rating =
        Rating.fromNameOrNull(value) ?: Rating.AGAIN
}
