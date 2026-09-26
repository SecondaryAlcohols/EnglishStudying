package com.alcohol.englishstudying

import com.alcohol.englishstudying.data.repo.ResumeAction
import com.alcohol.englishstudying.data.repo.resumeActionFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「移出学习」名单恢复一个词时走哪条路。
 *
 * 判据判错的后果是**静默的数据丢失**：把学过很久的词当成新词，删掉状态行，历史
 * ease / lapses 一起没了，还会重新占一次新词额度 —— 界面上完全看不出来。所以它必须
 * 能单测，而不是埋在一个要连数据库才能跑的 Repository 方法里。
 */
class ResumeActionTest {

    /** 真的一次都没学过（新词当天就标了太简单）→ 删行回新词。 */
    @Test
    fun `没有任何作答日志时回新词`() {
        assertEquals(
            ResumeAction.RESET_TO_NEW,
            resumeActionFor(hasLogs = false, repetitions = 0, intervalDays = 0.0)
        )
    }

    /**
     * 关键回归：学过、但被遗忘了。
     *
     * 遗忘会把 `repetitions` 和 `intervalDays` 一起清零（见 Sm2Scheduler 的 AGAIN 分支），
     * 于是这两个数与「真没学过」**完全一样** —— 唯一能区分的就是日志。原先按那两个数
     * 判断，这条路径会掉进 RESET_TO_NEW，把词的历史连同状态行一起删掉。
     */
    @Test
    fun `有日志但次数和间隔都被清零时走重学而不是回新词`() {
        assertEquals(
            ResumeAction.RELEARN,
            resumeActionFor(hasLogs = true, repetitions = 0, intervalDays = 0.0)
        )
    }

    /** 还有间隔在 → 接着复习。 */
    @Test
    fun `有间隔时接着复习`() {
        assertEquals(
            ResumeAction.RESUME_REVIEW,
            resumeActionFor(hasLogs = true, repetitions = 3, intervalDays = 10.0)
        )
    }

    /**
     * 边界：次数为 0 但间隔还在。
     *
     * 判据里 `repetitions == 0 && intervalDays <= 0.0` 是**与**关系，两者都成立才算
     * 「被遗忘了」；只满足一个就还得接着复习。
     */
    @Test
    fun `次数为零但间隔还在时不判成重学`() {
        assertEquals(
            ResumeAction.RESUME_REVIEW,
            resumeActionFor(hasLogs = true, repetitions = 0, intervalDays = 5.0)
        )
    }
}
