package com.lifeplanner.core.logic

import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.GoalLevels
import com.lifeplanner.core.model.GoalNode
import com.lifeplanner.core.model.GoalStatuses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GoalLogic 纯规则单测：JVM only，无 Android、无网络、无 Mockito。 */
class GoalLogicTest {

    private fun g(
        id: Long,
        parentId: Long? = null,
        level: String = GoalLevels.LONG,
        status: String = GoalStatuses.IN_PROGRESS,
        progress: Int = 0,
        deleted: Boolean = false,
    ) = Goal(
        id = id,
        parentId = parentId,
        level = level,
        title = "G$id",
        progress = progress,
        status = status,
        deletedAt = if (deleted) "2024-01-01T00:00:00Z" else null,
    )

    private fun flatten(nodes: List<GoalNode>): List<Goal> {
        val out = ArrayList<Goal>()
        fun walk(n: GoalNode) {
            out.add(n.goal)
            n.children.forEach { walk(it) }
        }
        nodes.forEach { walk(it) }
        return out
    }

    @Test
    fun `成环时两个目标都出现在树里且不重复`() {
        val tree = GoalLogic.buildTree(listOf(g(1, parentId = 2), g(2, parentId = 1)))

        val ids = flatten(tree).map { it.id }
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
        assertEquals(setOf(1L, 2L), ids.toSet())
        // 环内最小 id 被提升为根（父级断开），另一个成为它的子节点
        assertEquals(1L, tree.single().goal.id)
        assertNull(tree.single().goal.parentId)
        assertEquals(listOf(2L), tree.single().children.map { it.goal.id })
        assertEquals(1L, tree.single().children.single().goal.parentId)
    }

    @Test
    fun `三层正常链只产生一个根`() {
        val tree = GoalLogic.buildTree(
            listOf(
                g(1, level = GoalLevels.VISION),
                g(2, parentId = 1, level = GoalLevels.LONG),
                g(3, parentId = 2, level = GoalLevels.STAGE),
            ),
        )
        assertEquals(1, tree.size)
        assertEquals(1L, tree.single().goal.id)
        assertEquals(2L, tree.single().children.single().goal.id)
        assertEquals(3L, tree.single().children.single().children.single().goal.id)
    }

    @Test
    fun `父级指向不存在或已删除时作为根节点`() {
        val tree = GoalLogic.buildTree(listOf(g(1, parentId = 99), g(2, parentId = 1)))

        val ids = flatten(tree).map { it.id }
        assertEquals(2, ids.size)
        assertEquals(setOf(1L, 2L), ids.toSet())
        assertEquals(1L, tree.single().goal.id)
    }

    @Test
    fun `父级不能是它自己`() {
        val all = listOf(g(1, level = GoalLevels.VISION))
        assertEquals("父级目标不能是它自己", GoalLogic.validateParent(all, 1, 1))
    }

    @Test
    fun `父级不存在被拒绝`() {
        val all = listOf(g(1, level = GoalLevels.VISION))
        assertEquals("父级目标不存在或已被删除", GoalLogic.validateParent(all, 99, 1))
    }

    @Test
    fun `父级已删除被拒绝`() {
        val all = listOf(g(1, level = GoalLevels.VISION), g(4, level = GoalLevels.LONG, deleted = true))
        assertEquals("父级目标不存在或已被删除", GoalLogic.validateParent(all, 4, 1))
    }

    @Test
    fun `父级是自己的下级会成环被拒绝`() {
        val all = listOf(
            g(1, level = GoalLevels.VISION),
            g(2, parentId = 1, level = GoalLevels.LONG),
            g(3, parentId = 2, level = GoalLevels.STAGE),
        )
        assertEquals(
            "不能把自己的下级目标设为父级（会形成循环）",
            GoalLogic.validateParent(all, 3, 1),
        )
    }

    @Test
    fun `合法父级与无父级都通过`() {
        val all = listOf(
            g(1, level = GoalLevels.VISION),
            g(2, parentId = 1, level = GoalLevels.LONG),
        )
        assertNull(GoalLogic.validateParent(all, 1, 2))
        assertNull(GoalLogic.validateParent(all, null, 2))
    }

    @Test
    fun `descendantIds 含自身并收集整棵子树`() {
        val all = listOf(
            g(1, level = GoalLevels.VISION),
            g(2, parentId = 1, level = GoalLevels.LONG),
            g(3, parentId = 2, level = GoalLevels.STAGE),
            g(4),
        )
        assertEquals(setOf(1L, 2L, 3L), GoalLogic.descendantIds(all, 1))
        assertEquals(setOf(4L), GoalLogic.descendantIds(all, 4))
    }

    @Test
    fun `allowedParentIds 按层级过滤并排除自己`() {
        val all = listOf(
            g(1, level = GoalLevels.VISION),
            g(2, level = GoalLevels.LONG),
            g(3, level = GoalLevels.STAGE),
            g(4, level = GoalLevels.WEEKLY),
            g(5, level = GoalLevels.VISION, deleted = true),
        )
        // currentLevel=stage → 只允许 vision / long；排除自己(3)、排除已删除(5)、排除 weekly(4)
        assertEquals(listOf(1L, 2L), GoalLogic.allowedParentIds(all, 3, GoalLevels.STAGE).map { it.id })
        // 未知层级不做层级限制，但仍排除自己与已删除
        assertEquals(listOf(1L, 2L, 4L), GoalLogic.allowedParentIds(all, 3, "unknown").map { it.id })
    }

    @Test
    fun `nextStatusOnProgress 各分支`() {
        assertEquals(
            GoalStatuses.IN_PROGRESS,
            GoalLogic.nextStatusOnProgress(GoalStatuses.IN_PROGRESS, 50),
        )
        assertEquals(
            GoalStatuses.COMPLETED,
            GoalLogic.nextStatusOnProgress(GoalStatuses.IN_PROGRESS, 100),
        )
        assertEquals(
            GoalStatuses.IN_PROGRESS,
            GoalLogic.nextStatusOnProgress(GoalStatuses.COMPLETED, 40),
        )
        assertEquals(
            GoalStatuses.PAUSED,
            GoalLogic.nextStatusOnProgress(GoalStatuses.PAUSED, 50),
        )
        // paused + 100 → completed
        assertEquals(
            GoalStatuses.COMPLETED,
            GoalLogic.nextStatusOnProgress(GoalStatuses.PAUSED, 100),
        )
    }

    @Test
    fun `applyProgress 重复取最后一项非法被跳过合法仍成功`() {
        val goals = listOf(
            g(1, progress = 0),
            g(2, progress = 0),
            g(3, progress = 10, deleted = true),
            g(5, progress = 0),
        )
        val (after, skipped) = GoalLogic.applyProgress(
            goals,
            listOf(1L to 100, 1L to 40, 2L to 150, 3L to 30, 99L to 50, 5L to 100),
        )

        // 1 重复出现以最后一项 40 为准，状态保持进行中
        val g1 = after.first { it.id == 1L }
        assertEquals(40, g1.progress)
        assertEquals(GoalStatuses.IN_PROGRESS, g1.status)
        // 5 合法且到 100 → 完成
        val g5 = after.first { it.id == 5L }
        assertEquals(100, g5.progress)
        assertEquals(GoalStatuses.COMPLETED, g5.status)
        // 非法项未写入
        assertEquals(0, after.first { it.id == 2L }.progress)

        assertEquals(3, skipped.size)
        assertTrue(skipped.contains(2L to "进度必须在 0-100 之间"))
        assertTrue(skipped.contains(3L to "目标已删除"))
        assertTrue(skipped.contains(99L to "目标不存在"))
        // 纯函数：不改动入参
        assertEquals(0, goals.first { it.id == 1L }.progress)
    }
}
