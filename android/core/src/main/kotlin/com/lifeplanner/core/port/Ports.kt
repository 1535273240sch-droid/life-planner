package com.lifeplanner.core.port

import com.lifeplanner.core.model.AiMessage
import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.Profile
import com.lifeplanner.core.model.Skill
import com.lifeplanner.core.model.Task
import kotlinx.datetime.LocalDate

/**
 * 仓储与传输接口（契约）。
 *
 * 约定：
 * - 全部为同步阻塞方法；调用方负责切到 IO 线程（界面层用 Dispatchers.IO）。
 * - 实现放在 core/data（SQLDelight + Android 驱动），测试用 core test 里的 JVM 驱动。
 * - 业务规则**不要**写在仓储里：仓储只负责存取，规则在 core/logic 与 core/ai。
 * - 这些接口名/签名由主 agent 维护，agent 不要改；需要新增方法请在报告里提出。
 */

interface ProfileRepository {
    /** 单用户：没有记录时返回 null */
    fun get(): Profile?
    fun save(profile: Profile): Profile
}

interface SkillRepository {
    fun list(): List<Skill>
    fun add(skill: Skill): Skill
    fun update(skill: Skill)
    fun delete(id: Long)
    /** 导入用：清空后写入 */
    fun replaceAll(skills: List<Skill>)
}

interface GoalRepository {
    /** 默认不含已软删除目标 */
    fun list(includeDeleted: Boolean = false): List<Goal>
    fun findById(id: Long, includeDeleted: Boolean = false): Goal?
    /** 返回新 id */
    fun insert(goal: Goal): Long
    fun update(goal: Goal)
    /**
     * 软删除该目标及其整棵子树：只写 deleted_at，不删行、不动任务。
     * 目标不存在或已删除时返回 0。返回受影响的目标行数。
     */
    fun softDeleteSubtree(id: Long, deletedAtIso: String): Int
    fun replaceAll(goals: List<Goal>)
}

interface TaskRepository {
    fun listByDate(date: LocalDate): List<Task>
    fun listByGoal(goalId: Long): List<Task>
    fun insert(task: Task): Long
    fun setDone(id: Long, done: Boolean, doneAtIso: String?)
    fun delete(id: Long)
    /** 该目标在 since（含）之后已完成的任务数，用于目标停滞检测 */
    fun countDoneSince(goalId: Long, since: LocalDate): Int
    /** 全部任务，供导出使用（不要用逐日遍历代替） */
    fun listAll(): List<Task>
    fun replaceAll(tasks: List<Task>)
}

interface DailyLogRepository {
    fun findByDate(date: LocalDate): DailyLog?
    /** 含首尾 */
    fun listBetween(from: LocalDate, to: LocalDate): List<DailyLog>
    /** 一天一条：存在则更新，不存在则插入 */
    fun upsert(log: DailyLog): DailyLog
    /** 全部记录，供导出使用 */
    fun listAll(): List<DailyLog>
    fun replaceAll(logs: List<DailyLog>)
}

interface AiMessageRepository {
    /** 按 id 倒序；mode 为 null 时不过滤 */
    fun listByMode(mode: String?, limit: Int): List<AiMessage>
    fun insert(message: AiMessage): Long
    /** 全部记录，供导出使用 */
    fun listAll(): List<AiMessage>
    fun replaceAll(messages: List<AiMessage>)
    fun clear()
}

/** HTTP 传输失败的统一异常。 */
class HttpTransportException(
    val statusCode: Int,
    message: String,
) : Exception(message)

/**
 * AI 调用所需的 HTTP 能力。core 只依赖这个接口，
 * 由 app 模块用 OkHttp 实现（app/net），测试用假实现。
 */
interface HttpTransport {
    /**
     * 同步 POST 一个 JSON 请求体，返回响应体文本。
     * @param url 完整地址（由调用方拼好 /chat/completions）
     * @param bearerToken 放到 Authorization: Bearer 头
     * @throws HttpTransportException 非 2xx（带状态码）
     * @throws java.io.IOException 网络不通/超时（由实现抛出）
     */
    fun postJson(url: String, bearerToken: String, body: String, timeoutMs: Long): String
}
