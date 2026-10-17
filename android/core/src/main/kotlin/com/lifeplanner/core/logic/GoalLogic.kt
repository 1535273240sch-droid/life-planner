package com.lifeplanner.core.logic

import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.GoalLevels
import com.lifeplanner.core.model.GoalNode
import com.lifeplanner.core.model.GoalStatuses

/**
 * 目标领域的纯业务规则：无 IO、无仓储，全部可直接单测。
 * 语义与 v1 Python 版（services/goal_service.py）对齐。
 */
object GoalLogic {

    /**
     * 把（已排除软删除的）目标列表组装成树。
     *
     * - parentId 指向不存在（含已删除）的目标 → 该目标作为根。
     * - 成环兜底：先按父子挂载，再从根向下标记可达；不可达的节点（父级链闭合）
     *   从原父级摘除、parentId 置 null 后按 id 升序逐个提升为根，并重新标记可达。
     *   保证任何目标都不丢失、不重复。
     */
    fun buildTree(goals: List<Goal>): List<GoalNode> {
        val byId = LinkedHashMap<Long, Goal>()
        for (g in goals) {
            val id = g.id
            if (id != null) byId[id] = g
        }

        // 父子挂载：children 保序；无法挂到已有父级的作为根
        val children = HashMap<Long, MutableList<Long>>()
        val roots = ArrayList<Long>()
        val idless = ArrayList<Goal>() // 无 id 的目标无法被引用，直接作为根
        for (g in goals) {
            val id = g.id
            if (id == null) {
                idless.add(g)
                continue
            }
            val pid = g.parentId
            if (pid != null && byId.containsKey(pid)) {
                children.getOrPut(pid) { ArrayList() }.add(id)
            } else {
                roots.add(id)
            }
        }

        val reachable = HashSet<Long>()
        fun markFrom(start: Long) {
            val stack = ArrayDeque<Long>()
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                if (!reachable.add(cur)) continue
                children[cur]?.forEach { stack.addLast(it) }
            }
        }
        roots.forEach { markFrom(it) }

        // 兜底：环内节点挂不到任何根上，会在树里消失。按 id 升序在最小 id 处断开并提升为根。
        val unreachable = byId.keys.filter { it !in reachable }.sorted()
        for (gid in unreachable) {
            if (gid in reachable) continue
            val pid = byId[gid]!!.parentId
            if (pid != null) children[pid]?.remove(gid)
            byId[gid] = byId[gid]!!.copy(parentId = null) // 与 Python 兜底一致：断开父级
            roots.add(gid)
            markFrom(gid) // 把同一环上的其他节点一并带出来
        }

        // 递归构建，用全局 built 集合兜住任何重复/残留环
        val built = HashSet<Long>()
        fun build(id: Long): GoalNode {
            built.add(id)
            val kids = (children[id] ?: emptyList()).filter { it !in built }.map { build(it) }
            return GoalNode(byId[id]!!, kids)
        }
        val out = ArrayList<GoalNode>(roots.size + idless.size)
        for (id in roots) if (id !in built) out.add(build(id))
        for (id in byId.keys) if (id !in built) out.add(build(id)) // 防御性补漏
        idless.forEach { out.add(GoalNode(it)) }
        return out
    }

    /**
     * 校验父级目标是否可用。返回 null 表示通过，否则返回中文错误说明。
     * 规则：不能是自己；父级必须存在且未删除；沿父链上溯（带防环集合）不能遇到 goalId。
     */
    fun validateParent(allGoals: List<Goal>, parentId: Long?, goalId: Long?): String? {
        if (parentId == null) return null
        if (goalId != null && parentId == goalId) return "父级目标不能是它自己"
        val parent = allGoals.firstOrNull { it.id == parentId && it.deletedAt == null }
            ?: return "父级目标不存在或已被删除"
        if (goalId == null) return null

        val seen = HashSet<Long>()
        var cur: Goal? = parent
        while (cur != null) {
            val cid = cur.id ?: return null
            if (cid == goalId) return "不能把自己的下级目标设为父级（会形成循环）"
            if (!seen.add(cid)) return null
            cur = cur.parentId?.let { pid -> allGoals.firstOrNull { it.id == pid } }
        }
        return null
    }

    /** 以 rootId 为根的整棵子树 id（含 rootId 自身），带防环。 */
    fun descendantIds(allGoals: List<Goal>, rootId: Long): Set<Long> {
        val byParent = allGoals.filter { it.parentId != null }.groupBy { it.parentId }
        val out = HashSet<Long>()
        val stack = ArrayDeque<Long>()
        stack.addLast(rootId)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!out.add(cur)) continue
            byParent[cur]?.forEach { child -> child.id?.let { stack.addLast(it) } }
        }
        return out
    }

    /**
     * 供界面下拉：可选作父级的目标。排除自己、排除已删除、只保留比 currentLevel 更靠前的层级。
     * currentLevel 未知时（allowedParentLevels 返回 null）不做层级限制。
     */
    fun allowedParentIds(goals: List<Goal>, currentGoalId: Long?, currentLevel: String): List<Goal> {
        val allowedLevels = GoalLevels.allowedParentLevels(currentLevel)
        return goals.filter { g ->
            val id = g.id
            id != null && g.deletedAt == null && id != currentGoalId &&
                (allowedLevels == null || g.level in allowedLevels)
        }
    }

    /** 进度变化后的目标状态：>=100 完成；原完成且 <100 回到进行中；paused 仅在 <100 时保持。 */
    fun nextStatusOnProgress(currentStatus: String, newProgress: Int): String = when {
        newProgress >= 100 -> GoalStatuses.COMPLETED
        currentStatus == GoalStatuses.COMPLETED -> GoalStatuses.IN_PROGRESS
        else -> currentStatus
    }

    /**
     * 计算进度写入结果（不改动入参）。
     * 同一 goalId 重复出现以最后一项为准；不存在 / 已删除 / 超出 0..100 → 跳过并给中文原因。
     *
     * @return (更新后的完整目标列表, 被跳过的 (goalId, 原因))
     */
    fun applyProgress(
        goals: List<Goal>,
        items: List<Pair<Long, Int>>,
    ): Pair<List<Goal>, List<Pair<Long, String>>> {
        val latest = LinkedHashMap<Long, Int>() // 保序：首次出现位置 + 最后一项的值
        for ((id, progress) in items) latest[id] = progress

        val result = goals.toMutableList()
        val indexById = HashMap<Long, Int>()
        result.forEachIndexed { i, g -> g.id?.let { indexById[it] = i } }

        val skipped = ArrayList<Pair<Long, String>>()
        for ((id, progress) in latest) {
            val idx = indexById[id]
            if (idx == null) {
                skipped.add(id to "目标不存在")
                continue
            }
            val old = result[idx]
            if (old.deletedAt != null) {
                skipped.add(id to "目标已删除")
                continue
            }
            if (progress < 0 || progress > 100) {
                skipped.add(id to "进度必须在 0-100 之间")
                continue
            }
            result[idx] = old.copy(progress = progress, status = nextStatusOnProgress(old.status, progress))
        }
        return result to skipped
    }
}
