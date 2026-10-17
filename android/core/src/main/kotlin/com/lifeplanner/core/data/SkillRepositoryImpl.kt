package com.lifeplanner.core.data

import com.lifeplanner.core.db.LifePlannerDb
import com.lifeplanner.core.model.Skill
import com.lifeplanner.core.port.SkillRepository

/** 技能仓储实现：纯存取，无业务规则。 */
class SkillRepositoryImpl(private val db: LifePlannerDb) : SkillRepository {

    private val q get() = db.queriesQueries

    override fun list(): List<Skill> =
        q.selectAllSkills().executeAsList().map { row ->
            Skill(
                id = row.id,
                name = row.name,
                category = row.category,
                level = row.level.toInt(),
                note = row.note,
            )
        }

    override fun add(skill: Skill): Skill {
        val id = db.transactionWithResult {
            q.insertSkill(skill.name, skill.category, skill.level.toLong(), skill.note)
            q.lastInsertRowId().executeAsOne()
        }
        return skill.copy(id = id)
    }

    override fun update(skill: Skill) {
        val id = requireNotNull(skill.id) { "update 需要 skill.id" }
        q.updateSkill(skill.name, skill.category, skill.level.toLong(), skill.note, id)
    }

    override fun delete(id: Long) {
        q.deleteSkillById(id)
    }

    override fun replaceAll(skills: List<Skill>) {
        db.transaction {
            q.deleteAllSkills()
            for (s in skills) {
                if (s.id != null) {
                    q.insertSkillWithId(s.id, s.name, s.category, s.level.toLong(), s.note)
                } else {
                    q.insertSkill(s.name, s.category, s.level.toLong(), s.note)
                }
            }
        }
    }
}
