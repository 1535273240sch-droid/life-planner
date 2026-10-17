package com.lifeplanner.core.json

import com.lifeplanner.core.model.AiMessage
import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.Profile
import com.lifeplanner.core.model.Skill
import com.lifeplanner.core.model.Task
import com.lifeplanner.core.port.AiMessageRepository
import com.lifeplanner.core.port.DailyLogRepository
import com.lifeplanner.core.port.GoalRepository
import com.lifeplanner.core.port.ProfileRepository
import com.lifeplanner.core.port.SkillRepository
import com.lifeplanner.core.port.TaskRepository
import com.lifeplanner.core.util.Dates
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 导入模式。本期只实现 REPLACE；不实现 MERGE（语义含糊，避免误合并）。 */
enum class ImportMode { REPLACE }

/** 导入结果统计。 */
data class ImportSummary(
    val profileImported: Boolean,
    val skills: Int,
    val goals: Int,
    val tasks: Int,
    val dailyLogs: Int,
    val aiMessages: Int,
)

/**
 * JSON 备份服务：把本地全部数据导出为单个 JSON 文件，或从该文件导入。
 *
 * 关键约定：
 * - 导出含**已软删除目标**（`deletedAt` 一并导出），保证备份完整。
 * - 导入 REPLACE：先清空再写入。**所有 DTO 先转成领域模型并完成基本校验**，
 *   全部通过后才开始写库；任何一步失败即抛异常。
 * - **写入不是跨仓储事务**：若写库中途失败，已写入的仓储不会自动回滚，
 *   由调用方负责清理/回滚（本服务不做补偿）。
 * - 目标 id 会重映射：备份内的 `parentId` 引用旧 id，写入后用「旧 id → 新 id」
 *   映射修正父子关系；任务的 `goalId` 同样重映射（目标不存在则置 null）。
 */
class BackupService(
    private val profileRepository: ProfileRepository,
    private val skillRepository: SkillRepository,
    private val goalRepository: GoalRepository,
    private val taskRepository: TaskRepository,
    private val dailyLogRepository: DailyLogRepository,
    private val aiMessageRepository: AiMessageRepository,
) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** 导出全部数据为 JSON 字符串。 */
    fun exportJson(): String {
        val profile = profileRepository.get()
        val skills = skillRepository.list()
        // 含已软删除目标，保证备份完整
        val goals = goalRepository.list(includeDeleted = true)
        val dailyLogs = dailyLogRepository.listAll()
        val tasks = taskRepository.listAll()
        val aiMessages = aiMessageRepository.listAll()

        val backup = BackupFile(
            version = BACKUP_VERSION,
            exportedAt = Dates.nowIso(),
            profile = profile?.let { it.toDto() },
            skills = skills.map { it.toDto() },
            goals = goals.map { it.toDto() },
            tasks = tasks.map { it.toDto() },
            dailyLogs = dailyLogs.map { it.toDto() },
            aiMessages = aiMessages.map { it.toDto() },
        )
        return json.encodeToString(BackupFile.serializer(), backup)
    }

    /**
     * 从 JSON 字符串导入（REPLACE：先清空再写入）。
     * @throws IllegalArgumentException 解析失败 / 版本不支持 / 数据校验不通过。
     */
    fun importJson(text: String, mode: ImportMode): ImportSummary {
        when (mode) {
            ImportMode.REPLACE -> Unit
        }

        // 1) JSON 解析
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("备份文件不是合法的 JSON")
        }
        val rootObj = root as? JsonObject
            ?: throw IllegalArgumentException("备份文件不是合法的 JSON")

        // 2) 版本校验（缺失或非 1 一律拒绝）
        val versionEl = rootObj["version"]
            ?: throw IllegalArgumentException("不支持的备份版本：缺少 version 字段")
        val version = (versionEl as? JsonPrimitive)?.content?.toIntOrNull()
            ?: throw IllegalArgumentException("不支持的备份版本：${versionEl.toString()}")
        if (version != BACKUP_VERSION) {
            throw IllegalArgumentException("不支持的备份版本：$version")
        }

        // 3) 结构解析
        val backup = try {
            json.decodeFromJsonElement(BackupFile.serializer(), root)
        } catch (e: Exception) {
            throw IllegalArgumentException("备份文件结构不正确：${e.message}")
        }

        // 4) 内存中完成全部转换与校验；这一步之前不写库
        val profile = backup.profile?.toDomain()
        val skills = backup.skills.map { it.toDomainChecked() }
        val goals = backup.goals.map { it.toDomainChecked() }
        val tasks = backup.tasks.map { it.toDomainChecked() }
        val dailyLogs = backup.dailyLogs.map { it.toDomainChecked() }
        val aiMessages = backup.aiMessages.map { it.toDomain() }

        // 5) 写入（顺序执行；失败由调用方负责回滚）
        val profileImported = profile != null
        profile?.let { profileRepository.save(it) }

        // 技能无交叉引用，直接替换
        skillRepository.replaceAll(skills)

        // 目标：先清空，再逐条 insert 以获得新 id，并建立 旧id→新id 映射
        goalRepository.replaceAll(emptyList())
        val idMap = HashMap<Long, Long>()
        val pending = goals.toMutableList()
        var progressed = true
        while (pending.isNotEmpty() && progressed) {
            progressed = false
            val iter = pending.iterator()
            while (iter.hasNext()) {
                val g = iter.next()
                val oldId = g.id
                val oldParent = g.parentId
                // 父级为空 / 自指 / 父级已写入 → 可解析
                val resolvable = oldParent == null || oldParent == oldId || idMap.containsKey(oldParent)
                if (resolvable) {
                    val newParent = if (oldParent == null || oldParent == oldId) null else idMap[oldParent]
                    val newId = goalRepository.insert(g.copy(id = null, parentId = newParent))
                    if (oldId != null) idMap[oldId] = newId
                    iter.remove()
                    progressed = true
                }
            }
        }
        // 剩余：父级缺失或成环，退化为根节点（不丢数据）
        for (g in pending) {
            val newId = goalRepository.insert(g.copy(id = null, parentId = null))
            if (g.id != null) idMap[g.id] = newId
        }

        // 任务：先清空再写入；goalId 用映射修正，目标不存在则置 null
        taskRepository.replaceAll(emptyList())
        for (t in tasks) {
            val newGoalId = t.goalId?.let { idMap[it] }
            taskRepository.insert(t.copy(id = null, goalId = newGoalId))
        }

        // 每日记录：按日期 upsert，无交叉引用，直接替换
        dailyLogRepository.replaceAll(dailyLogs)

        // AI 记录：无交叉引用，直接替换
        aiMessageRepository.replaceAll(aiMessages)

        return ImportSummary(
            profileImported = profileImported,
            skills = skills.size,
            goals = goals.size,
            tasks = tasks.size,
            dailyLogs = dailyLogs.size,
            aiMessages = aiMessages.size,
        )
    }

    // ---- DTO → 领域模型（含校验） ----

    private fun ProfileDto.toDomain() = Profile(
        id = null,
        age = age,
        gender = gender,
        occupation = occupation,
        industry = industry,
        yearsOfWork = yearsOfWork,
        education = education,
        major = major,
        healthInfo = healthInfo,
        vision = vision,
        constraints = constraints,
        updatedAt = updatedAt,
    )

    private fun SkillDto.toDomainChecked(): Skill {
        if (name.isBlank()) throw IllegalArgumentException("技能名称不能为空")
        return Skill(id = null, name = name, category = category, level = level, note = note)
    }

    private fun GoalDto.toDomainChecked(): Goal {
        if (title.isBlank()) throw IllegalArgumentException("目标标题不能为空")
        return Goal(
            id = id,
            parentId = parentId,
            level = level,
            title = title,
            category = category,
            description = description,
            metric = metric,
            startDate = optionalDate(startDate, "目标开始日期"),
            dueDate = optionalDate(dueDate, "目标截止日期"),
            progress = progress.coerceIn(0, 100), // 越界夹到 0..100
            status = status,
            deletedAt = deletedAt,
        )
    }

    private fun TaskDto.toDomainChecked(): Task {
        if (title.isBlank()) throw IllegalArgumentException("任务标题不能为空")
        return Task(
            id = id,
            goalId = goalId,
            date = requireDate(date, "任务日期"),
            title = title,
            estMinutes = estMinutes,
            done = done,
            doneAt = doneAt,
        )
    }

    private fun DailyLogDto.toDomainChecked(): DailyLog = DailyLog(
        id = id,
        date = requireDate(date, "每日记录日期"),
        doneItems = doneItems,
        studyMinutes = studyMinutes,
        studyNote = studyNote,
        meals = meals,
        sleepStart = sleepStart,
        sleepEnd = sleepEnd,
        exerciseType = exerciseType,
        exerciseMinutes = exerciseMinutes,
        mood = mood,
        energy = energy,
        reflection = reflection,
    )

    private fun AiMessageDto.toDomain() = AiMessage(
        id = null,
        mode = mode,
        question = question,
        answer = answer,
        source = source,
        modelName = modelName,
        createdAt = createdAt,
    )

    private fun optionalDate(iso: String?, label: String): LocalDate? {
        if (iso.isNullOrBlank()) return null
        return Dates.parse(iso) ?: throw IllegalArgumentException("$label 不是合法日期：$iso")
    }

    private fun requireDate(iso: String?, label: String): LocalDate {
        if (iso.isNullOrBlank()) throw IllegalArgumentException("$label 不能为空")
        return Dates.parse(iso) ?: throw IllegalArgumentException("$label 不是合法日期：$iso")
    }

    // ---- 领域模型 → DTO ----

    private fun Profile.toDto() = ProfileDto(
        age, gender, occupation, industry, yearsOfWork, education, major,
        healthInfo, vision, constraints, updatedAt,
    )

    private fun Skill.toDto() = SkillDto(id, name, category, level, note)

    private fun Goal.toDto() = GoalDto(
        id, parentId, level, title, category, description, metric,
        startDate?.toString(), dueDate?.toString(), progress, status, deletedAt,
    )

    private fun Task.toDto() = TaskDto(id, goalId, date.toString(), title, estMinutes, done, doneAt)

    private fun DailyLog.toDto() = DailyLogDto(
        id, date.toString(), doneItems, studyMinutes, studyNote, meals,
        sleepStart, sleepEnd, exerciseType, exerciseMinutes, mood, energy, reflection,
    )

    private fun AiMessage.toDto() = AiMessageDto(id, mode, question, answer, source, modelName, createdAt)

    companion object {
        const val BACKUP_VERSION = 1
    }
}
