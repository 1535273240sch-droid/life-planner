package com.lifeplanner.core.data

import com.lifeplanner.core.db.LifePlannerDb
import com.lifeplanner.core.model.Task
import com.lifeplanner.core.port.TaskRepository
import com.lifeplanner.core.util.Dates
import kotlinx.datetime.LocalDate

/** 任务仓储实现。date 存 ISO 字符串，读出时解析；解析失败的行跳过（不崩）。 */
class TaskRepositoryImpl(private val db: LifePlannerDb) : TaskRepository {

    private val q get() = db.queriesQueries

    // 生成的行属性沿用库列名：goal_id / est_minutes / done_at。

    override fun listByDate(date: LocalDate): List<Task> =
        q.selectTasksByDate(date.toString()).executeAsList().mapNotNull { row ->
            val d = Dates.parse(row.date) ?: return@mapNotNull null
            Task(
                id = row.id,
                goalId = row.goal_id,
                date = d,
                title = row.title,
                estMinutes = row.est_minutes.toInt(),
                done = row.done != 0L,
                doneAt = row.done_at,
            )
        }

    override fun listByGoal(goalId: Long): List<Task> =
        q.selectTasksByGoal(goalId).executeAsList().mapNotNull { row ->
            val d = Dates.parse(row.date) ?: return@mapNotNull null
            Task(
                id = row.id,
                goalId = row.goal_id,
                date = d,
                title = row.title,
                estMinutes = row.est_minutes.toInt(),
                done = row.done != 0L,
                doneAt = row.done_at,
            )
        }

    override fun listAll(): List<Task> =
        q.selectAllTasks().executeAsList().mapNotNull { row ->
            val d = Dates.parse(row.date) ?: return@mapNotNull null
            Task(
                id = row.id,
                goalId = row.goal_id,
                date = d,
                title = row.title,
                estMinutes = row.est_minutes.toInt(),
                done = row.done != 0L,
                doneAt = row.done_at,
            )
        }

    override fun insert(task: Task): Long = db.transactionWithResult {
        q.insertTask(
            task.goalId, task.date.toString(), task.title, task.estMinutes.toLong(),
            if (task.done) 1L else 0L, task.doneAt,
        )
        q.lastInsertRowId().executeAsOne()
    }

    override fun setDone(id: Long, done: Boolean, doneAtIso: String?) {
        q.updateTaskDone(if (done) 1L else 0L, doneAtIso, id)
    }

    override fun delete(id: Long) {
        q.deleteTaskById(id)
    }

    override fun countDoneSince(goalId: Long, since: LocalDate): Int =
        q.countTasksDoneSince(goalId, since.toString()).executeAsOne().toInt()

    override fun replaceAll(tasks: List<Task>) {
        // 先清空：空列表也必须清表（导入 REPLACE 依赖此语义）
        db.transaction {
            q.deleteAllTasks()
            for (t in tasks) {
                val date = t.date.toString()
                val done = if (t.done) 1L else 0L
                if (t.id != null) {
                    q.insertTaskWithId(t.id, t.goalId, date, t.title, t.estMinutes.toLong(), done, t.doneAt)
                } else {
                    q.insertTask(t.goalId, date, t.title, t.estMinutes.toLong(), done, t.doneAt)
                }
            }
        }
    }
}
