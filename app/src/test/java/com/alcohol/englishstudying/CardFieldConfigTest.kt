package com.alcohol.englishstudying

import com.alcohol.englishstudying.data.prefs.SettingsRepository
import com.alcohol.englishstudying.domain.model.CardField
import com.alcohol.englishstudying.domain.model.CardFieldConfig
import com.alcohol.englishstudying.domain.model.CardFieldConfigDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFieldConfigTest {

    @Test
    fun `默认配置包含全部九个字段且全部启用`() {
        val config = CardFieldConfigDefaults.default()

        assertEquals(CardField.entries.size, config.size)
        assertTrue(config.all { it.enabled })
    }

    @Test
    fun `默认顺序就是枚举声明顺序`() {
        val config = CardFieldConfigDefaults.default()

        assertEquals(CardField.entries.toList(), config.map { it.field })
    }

    @Test
    fun `巧记 词根 变形 默认是折叠的`() {
        val config = CardFieldConfigDefaults.default().associateBy { it.field }

        assertFalse(config.getValue(CardField.MNEMONIC).expanded)
        assertFalse(config.getValue(CardField.ROOTS).expanded)
        assertFalse(config.getValue(CardField.DERIVATIONS).expanded)
        assertTrue(config.getValue(CardField.HEADWORD).expanded)
        assertTrue(config.getValue(CardField.MEANINGS).expanded)
    }

    @Test
    fun `编码解码后顺序和开关都保持不变`() {
        val original = listOf(
            CardFieldConfig(CardField.ROOTS, enabled = true, expanded = true),
            CardFieldConfig(CardField.HEADWORD, enabled = true, expanded = true),
            CardFieldConfig(CardField.MNEMONIC, enabled = false, expanded = false)
        )

        val restored = SettingsRepository.decodeCardFields(
            SettingsRepository.encodeCardFields(original)
        )

        // 前三个应当原样保留；其余字段会被补在末尾
        assertEquals(original, restored.take(3))
        assertEquals(CardField.entries.size, restored.size)
    }

    @Test
    fun `旧配置里缺失的字段会被补上，不会被静默丢掉`() {
        // 模拟「存过配置之后又给枚举加了新字段」的情况
        val onlyTwo = listOf(
            CardFieldConfig(CardField.HEADWORD, true, true),
            CardFieldConfig(CardField.MEANINGS, true, true)
        )

        val restored = SettingsRepository.decodeCardFields(
            SettingsRepository.encodeCardFields(onlyTwo)
        )

        assertEquals(CardField.entries.size, restored.size)
        CardField.entries.forEach { field ->
            assertTrue("缺少字段 $field", restored.any { it.field == field })
        }
    }

    @Test
    fun `配置为空或损坏时退回默认值而不是崩溃`() {
        assertEquals(CardFieldConfigDefaults.default(), SettingsRepository.decodeCardFields(null))
        assertEquals(CardFieldConfigDefaults.default(), SettingsRepository.decodeCardFields(""))
        assertEquals(
            CardFieldConfigDefaults.default(),
            SettingsRepository.decodeCardFields("{ 这不是 JSON")
        )
    }

    @Test
    fun `认不出的字段名被忽略，其余照常解析`() {
        val mixed = """
            [
              {"key": "HEADWORD", "enabled": true, "expanded": true},
              {"key": "SOME_FUTURE_FIELD", "enabled": true, "expanded": true},
              {"key": "ROOTS", "enabled": true, "expanded": false}
            ]
        """.trimIndent()

        val restored = SettingsRepository.decodeCardFields(mixed)

        assertTrue(restored.any { it.field == CardField.HEADWORD })
        assertTrue(restored.any { it.field == CardField.ROOTS })
        // 认不出的那个被丢掉，其余字段补齐
        assertEquals(CardField.entries.size, restored.size)
    }

    @Test
    fun `没有重复字段`() {
        val duplicated = """
            [
              {"key": "ROOTS", "enabled": true, "expanded": true},
              {"key": "ROOTS", "enabled": false, "expanded": false}
            ]
        """.trimIndent()

        val restored = SettingsRepository.decodeCardFields(duplicated)

        assertEquals(restored.size, restored.map { it.field }.distinct().size)
    }
}
