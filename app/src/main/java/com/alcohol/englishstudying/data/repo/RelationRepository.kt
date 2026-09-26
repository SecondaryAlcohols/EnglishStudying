package com.alcohol.englishstudying.data.repo

import androidx.room.withTransaction
import com.alcohol.englishstudying.data.local.AppDatabase
import com.alcohol.englishstudying.data.local.RelatedWord
import com.alcohol.englishstudying.data.local.RelationSource
import com.alcohol.englishstudying.data.local.WordRelationEntity
import com.alcohol.englishstudying.domain.model.WordRelationType
import com.alcohol.englishstudying.domain.relations.RelationBuilder
import kotlinx.coroutines.flow.Flow

/** 一次重建的结果，用于给用户一个「做了多少事」的回执。 */
data class RebuildResult(
    val root: Int,
    val form: Int,
    val synonym: Int,
    val total: Int
)

class RelationRepository(private val db: AppDatabase) {

    private val dao = db.relationDao()

    fun observeFor(wordId: Long): Flow<List<RelatedWord>> = dao.observeFor(wordId)

    suspend fun getFor(wordId: Long): List<RelatedWord> = dao.getFor(wordId)

    /**
     * 手工添加一条关联。
     *
     * 标成 [RelationSource.MANUAL]，重建时不会被清掉。
     */
    suspend fun addManual(a: Long, b: Long, type: WordRelationType) {
        if (a == b) return
        // 规范化成 from < to，与推导产出的形状保持一致，否则主键去重会失效、
        // 同一条关系能从两个方向各插一行
        val from = if (a <= b) a else b
        val to = if (a <= b) b else a
        dao.insert(
            WordRelationEntity(
                fromWordId = from,
                toWordId = to,
                type = type,
                label = null,
                source = RelationSource.MANUAL
            )
        )
    }

    suspend fun remove(a: Long, b: Long, type: WordRelationType) = dao.deletePair(a, b, type)

    /**
     * 重建全部自动关联。
     *
     * 事务内先删掉所有 AUTO 行再重算 —— 这样改了 roots / synonyms 重新导入后，
     * 旧关系不会残留。MANUAL 的行一条都不碰。
     *
     * 已知行为：**用户手动删掉的 AUTO 关系会被重建找回来**。要修得再记一张
     * 「已忽略」表，代价不成比例，暂不处理（README 里写明了）。
     */
    suspend fun rebuild(): RebuildResult {
        var result = RebuildResult(0, 0, 0, 0)

        db.withTransaction {
            val inputs = dao.relationInputs().map {
                RelationBuilder.Input(
                    id = it.id,
                    headword = it.headword,
                    roots = it.roots,
                    derivations = it.derivations,
                    synonyms = it.synonyms
                )
            }
            val relations = RelationBuilder.build(inputs)

            dao.deleteAuto()
            if (relations.isNotEmpty()) {
                dao.insertAll(
                    relations.map {
                        WordRelationEntity(
                            fromWordId = it.from,
                            toWordId = it.to,
                            type = it.type,
                            label = it.label,
                            source = RelationSource.AUTO
                        )
                    }
                )
            }

            result = RebuildResult(
                root = relations.count { it.type == WordRelationType.ROOT },
                form = relations.count { it.type == WordRelationType.FORM },
                synonym = relations.count { it.type == WordRelationType.SYNONYM },
                total = relations.size
            )
        }
        return result
    }

    /**
     * 关联表是空的、但词库里有词时，补建一次。
     *
     * 覆盖两个场景：老版本升级上来（迁移只建表不填数据），以及导入前就已经有词的库。
     * 没有这一步，用户装完打开会看到三个字段全空，以为功能坏了 —— 而变形链本来
     * 就不需要任何额外数据，全靠库内词形匹配就能出来几千条。
     *
     * 只在表为空时跑，所以正常启动不会每次都重算。
     */
    suspend fun ensureBuilt() {
        if (dao.countAll() > 0) return
        if (dao.relationInputs().isEmpty()) return
        rebuild()
    }

    /** 各类关系当前的条数，设置页显示用。 */
    suspend fun counts(): Triple<Int, Int, Int> = Triple(
        dao.countByType(WordRelationType.ROOT),
        dao.countByType(WordRelationType.FORM),
        dao.countByType(WordRelationType.SYNONYM)
    )
}
