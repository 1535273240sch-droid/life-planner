package com.lifeplanner.core.data

import com.lifeplanner.core.db.LifePlannerDb
import com.lifeplanner.core.model.AiMessage
import com.lifeplanner.core.port.AiMessageRepository

/** AI 消息仓储实现：按 id 倒序取近期记录，mode 为空时不过滤。 */
class AiMessageRepositoryImpl(private val db: LifePlannerDb) : AiMessageRepository {

    private val q get() = db.queriesQueries

    // 生成的行属性沿用库列名：model_name / created_at。

    override fun listByMode(mode: String?, limit: Int): List<AiMessage> {
        val rows = if (mode == null) {
            q.selectRecentAiMessages(limit.toLong()).executeAsList()
        } else {
            q.selectAiMessagesByMode(mode, limit.toLong()).executeAsList()
        }
        return rows.map { row ->
            AiMessage(
                id = row.id,
                mode = row.mode,
                question = row.question,
                answer = row.answer,
                source = row.source,
                modelName = row.model_name,
                createdAt = row.created_at,
            )
        }
    }

    override fun listAll(): List<AiMessage> =
        q.selectAllAiMessages().executeAsList().map { row ->
            AiMessage(
                id = row.id,
                mode = row.mode,
                question = row.question,
                answer = row.answer,
                source = row.source,
                modelName = row.model_name,
                createdAt = row.created_at,
            )
        }

    override fun insert(message: AiMessage): Long = db.transactionWithResult {
        q.insertAiMessage(
            message.mode, message.question, message.answer, message.source,
            message.modelName, message.createdAt,
        )
        q.lastInsertRowId().executeAsOne()
    }

    override fun replaceAll(messages: List<AiMessage>) {
        // 先清空：空列表也必须清表（导入 REPLACE 依赖此语义）
        db.transaction {
            q.deleteAllAiMessages()
            for (m in messages) {
                if (m.id != null) {
                    q.insertAiMessageWithId(
                        m.id, m.mode, m.question, m.answer, m.source, m.modelName, m.createdAt,
                    )
                } else {
                    q.insertAiMessage(m.mode, m.question, m.answer, m.source, m.modelName, m.createdAt)
                }
            }
        }
    }

    override fun clear() {
        q.deleteAllAiMessages()
    }
}
