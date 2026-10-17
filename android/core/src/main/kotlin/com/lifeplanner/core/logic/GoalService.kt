package com.lifeplanner.core.logic

import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.GoalNode
import com.lifeplanner.core.model.GoalStatuses
import com.lifeplanner.core.port.GoalRepository
import com.lifeplanner.core.port.TaskRepository
import com.lifeplanner.core.util.Dates
import kotlinx.datetime.LocalDate

/**
 * 目标编排：把 [GoalLogic] 的纯规则接到仓储上。仓储由构造参数注入，测试用假实现。
 */
class GoalService(
    private val repo: GoalRepository,
    private val taskRepo: TaskRepository,
) {

    /** 用户确认后的进度写入结果：成功更新的目标 + 被跳过的 (goalId, 原因)。 */
    data class ProgressApplyResult(
        val updated: List<Goal>,
        val skipped: List<Pair<Long, String>>,
    )

    /** 新建目标：先校验父级（全量活目标），不通过抛 IllegalArgumentException（中文说明）。 */
    fun createGoal(goal: Goal): Long {
        val err = GoalLogic.validateParent(repo.list(includeDeleted = false), goal.parentId, goal.id)
        if (err != null) throw IllegalArgumentException(err)
        return repo.insert(goal)
    }

    /** 更新目标：目标不存在或已删除抛 NoSuchElementException；父级校验同新建。 */
    fun updateGoal(goal: Goal) {
        val id = goal.id ?: throw NoSuchElementException("目标不存在")
        repo.findById(id, includeDeleted = false) ?: throw NoSuchElementException("目标不存在")
        val err = GoalLogic.validateParent(repo.list(includeDeleted = false), goal.parentId, id)
        if (err != null) throw IllegalArgumentException(err)
        repo.update(goal)
    }

    /** 软删除目标及其整棵子树；返回是否删到（目标不存在或已删除为 false）。 */
    fun deleteGoal(id: Long): Boolean = repo.softDeleteSubtree(id, Dates.nowIso()) > 0

    fun tree(): List<GoalNode> = GoalLogic.buildTree(repo.list(includeDeleted = false))

    fun list(includeDeleted: Boolean = false): List<Goal> = repo.list(includeDeleted)

    /**
     * 用户确认后写入目标进度。**只在用户确认路径调用**，AI 路径绝不能调。
     * 取 includeDeleted=true 才能区分"不存在"与"已删除"。
     */
    fun confirmProgress(items: List<Pair<Long, Int>>): ProgressApplyResult {
        val before = repo.list(includeDeleted = true)
        val (after, skipped) = GoalLogic.applyProgress(before, items)
        val beforeById = before.associateBy { it.id }
        val applied = after.filter { g -> g.id != null && beforeById[g.id] != g }
        applied.forEach { repo.update(it) }
        return ProgressApplyResult(applied, skipped)
    }

    /** 停滞候选：进行中、未删除、且自 since 起无已完成任务（供缺口检测做关键词二次判断）。 */
    fun goalStagnantCandidates(since: LocalDate): List<Goal> =
        repo.list(includeDeleted = false).filter { g ->
            val id = g.id
            id != null && g.status == GoalStatuses.IN_PROGRESS && taskRepo.countDoneSince(id, since) == 0
        }
}
