package com.alcohol.englishstudying

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `AppDatabase` 的版本号必须有配套的迁移路径。
 *
 * 这个用例存在的唯一理由：2026-09-26 有人把 `version` 从 2 升到 3（同时注册了一张新实体）
 * 却没写 `MIGRATION_2_3`，而当时 `build()` 里挂着 `fallbackToDestructiveMigration()` ——
 * 用户装上新包一打开，Room 找不到迁移路径，**把整个学习库删表重建**：3271 个词、
 * 554 条记忆状态、2032 条作答日志全没。
 *
 * 那个兜底现在已经拿掉（缺迁移会启动即崩，数据保住），但「改 schema 忘了写迁移」这个
 * 动作本身还是得在**装机之前**就被挡住 —— 这就是本用例的职责。
 *
 * 手段是读源码文本：`version = N` 写在 `@Database` 上，迁移都是一个文件里的
 * `object : Migration(a, b)`，扫一遍就能判断从 1 能不能走到 N（允许多跳，如 1→2→3）。
 * 这个写法对格式变化是脆的 —— 但脆的方向是**失败**（会有人来看），不是悄悄放行。
 */
class MigrationChainTest {

    private val source: String by lazy {
        // 单测的工作目录是模块目录（app/），但两种都试一下，免得换个 Gradle 版本就找不到
        val candidates = listOf(
            "src/main/java/com/alcohol/englishstudying/data/local/AppDatabase.kt",
            "app/src/main/java/com/alcohol/englishstudying/data/local/AppDatabase.kt"
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 AppDatabase.kt（工作目录 = ${File(".").absolutePath}）")
        file.readText()
    }

    @Test
    fun `声明的迁移必须能把 1 号版本一路连到当前版本`() {
        val version = Regex("""version\s*=\s*(\d+)""").find(source)
            ?.groupValues?.get(1)?.toInt()
            ?: error("读不出 @Database 的 version")

        val edges = Regex("""Migration\((\d+)\s*,\s*(\d+)\)""").findAll(source)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()

        // 从 1 出发，反复套用已声明的迁移，看最远能走到哪
        val reachable = mutableSetOf(1)
        var grew = true
        while (grew) {
            grew = false
            for ((from, to) in edges) {
                if (from in reachable && reachable.add(to)) grew = true
            }
        }

        assertTrue(
            "version = $version，但声明的迁移 $edges 从 1 号版本走不到它。\n" +
                "改了 entities / 任何 @Entity / @ColumnInfo 就必须升 version 并写对应的 " +
                "Migration —— 兜底已经拿掉，漏写会让用户升级即崩。",
            version in reachable
        )
    }
}
