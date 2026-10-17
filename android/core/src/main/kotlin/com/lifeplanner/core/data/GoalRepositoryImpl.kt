package com.lifeplanner.core.data

import com.lifeplanner.core.db.LifePlannerDb
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.port.GoalRepository
import com.lifeplanner.core.util.Dates

/** 目标仓储实现：读取默认排除软删除；软删除级联整棵子树，不动任务。 */
class GoalRepositoryImpl(private val db: LifePlannerDb) : GoalRepository {

    private val q get() = db.queriesQueries

    // SQLDelight 生成的行属性沿用库列名（parent_id / start_date / deleted_at 等），不转驼峰。

    override fun list(includeDeleted: Boolean): List<Goal> {
        val rows = if (includeDeleted) {
            q.selectAllGoalsIncludingDeleted().executeAsList()
        } else {
            q.selectAllGoals().executeAsList()
        }
        return rows.map { row ->
            Goal(
                id = row.id,
                parentId = row.parent_id,
                level = row.level,
                title = row.title,
                category = row.category,
                description = row.description,
                metric = row.metric,
                startDate = Dates.parse(row.start_date),
                dueDate = Dates.parse(row.due_date),
                progress = row.progress.toInt(),
                status = row.status,
                deletedAt = row.deleted_at,
            )
        }
    }

    override fun findById(id: Long, includeDeleted: Boolean): Goal? {
        val row = if (includeDeleted) {
            q.selectGoalByIdIncludingDeleted(id).executeAsOneOrNull()
        } else {
            q.selectGoalById(id).executeAsOneOrNull()
        } ?: return null
        return Goal(
            id = row.id,
            parentId = row.parent_id,
            level = row.level,
            title = row.title,
            category = row.category,
            description = row.description,
            metric = row.metric,
            startDate = Dates.parse(row.start_date),
            dueDate = Dates.parse(row.due_date),
            progress = row.progress.toInt(),
            status = row.status,
            deletedAt = row.deleted_at,
        )
    }

    override fun insert(goal: Goal): Long = db.transactionWithResult {
        q.insertGoal(
            goal.parentId, goal.level, goal.title, goal.category, goal.description, goal.metric,
            goal.startDate?.toString(), goal.dueDate?.toString(), goal.progress.toLong(),
            goal.status, goal.deletedAt,
        )
        q.lastInsertRowId().executeAsOne()
    }

    override fun update(goal: Goal) {
        val id = requireNotNull(goal.id) { "update 需要 goal.id" }
        q.updateGoal(
            goal.parentId, goal.level, goal.title, goal.category, goal.description, goal.metric,
            goal.startDate?.toString(), goal.dueDate?.toString(), goal.progress.toLong(),
            goal.status, goal.deletedAt, id,
        )
    }

    /**
     * 软删除该目标及其整棵子树：只写 deleted_at，不删行、不动任务。
     * 用逐层查询 parent_id 的迭代遍历（不用递归 CTE），带 visited 集合防环。
     * 返回本次真正被标记（此前未删除）的行数；目标不存在或已删除时返回 0。
     */
    override fun softDeleteSubtree(id: Long, deletedAtIso: String): Int = db.transactionWithResult {
        // 目标必须存在且未删除
        if (q.selectGoalById(id).executeAsOneOrNull() == null) return@transactionWithResult 0

        val visited = HashSet<Long>()
        val live = ArrayList<Long>()
        val stack = ArrayDeque<Long>()
        stack.addLast(id)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!visited.add(cur)) continue          // 成环时不重复访问
            if (q.selectGoalById(cur).executeAsOneOrNull() != null) live.add(cur)
            // 子节点（含已删除的）继续下探，保证整棵子树都被覆盖
            for (child in q.selectChildGoalIds(cur).executeAsList()) {
                if (!visited.contains(child)) stack.addLast(child)
            }
        }
        for (goalId in live) q.softDeleteGoalById(deletedAtIso, goalId)
        live.size
    }

    override fun replaceAll(goals: List<Goal>) {
        db.transaction {
            q.deleteAllGoals()
            for (g in goals) {
                val start = g.startDate?.toString()
                val due = g.dueDate?.toString()
                if (g.id != null) {
                    q.insertGoalWithId(
                        g.id, g.parentId, g.level, g.title, g.category, g.description, g.metric,
                        start, due, g.progress.toLong(), g.status, g.deletedAt,
                    )
                } else {
                    q.insertGoal(
                        g.parentId, g.level, g.title, g.category, g.description, g.metric,
                        start, due, g.progress.toLong(), g.status, g.deletedAt,
                    )
                }
            }
        }
    }
}
