package com.lifeplanner.core.json

import com.lifeplanner.core.model.AiSources
import com.lifeplanner.core.model.GoalLevels
import com.lifeplanner.core.model.GoalStatuses
import kotlinx.serialization.Serializable

/**
 * JSON 备份的传输结构（DTO）。
 *
 * 字段命名约定（重要，导出/导入共用这套 DTO，保证自洽）：
 * - JSON 字段名一律 **camelCase**，与 Kotlin 属性名一致（kotlinx-serialization 默认行为），
 *   例如 goalId / dailyLogs / deletedAt / estMinutes。**不使用 snake_case**。
 * - 日期字段用 ISO 字符串 `YYYY-MM-DD`；时间戳字段（exportedAt/deletedAt/doneAt/createdAt/updatedAt）
 *   用 ISO 时间戳字符串。
 * - meals / healthInfo 用 `Map<String, String>`。
 *
 * 版本约定：version 当前固定为 1；导入时 version 缺失或 != 1 一律拒绝。
 *
 * 容错约定：所有集合字段默认 emptyList()、profile 默认 null、其余标量字段均给默认值，
 * 因此缺字段的旧文件仍可解析（业务校验交给 BackupService 做，给出可读中文错误）。
 */

@Serializable
data class BackupFile(
    val version: Int = 1,
    val exportedAt: String = "",                 // ISO 时间戳字符串；缺省为空串（备而不一定有值）
    val profile: ProfileDto? = null,
    val skills: List<SkillDto> = emptyList(),
    val goals: List<GoalDto> = emptyList(),
    val tasks: List<TaskDto> = emptyList(),
    val dailyLogs: List<DailyLogDto> = emptyList(),
    val aiMessages: List<AiMessageDto> = emptyList(),
)

@Serializable
data class ProfileDto(
    val age: Int? = null,
    val gender: String? = null,
    val occupation: String? = null,
    val industry: String? = null,
    val yearsOfWork: Int? = null,
    val education: String? = null,
    val major: String? = null,
    val healthInfo: Map<String, String> = emptyMap(),
    val vision: String? = null,
    val constraints: String? = null,
    val updatedAt: String? = null,               // ISO 时间戳字符串
)

@Serializable
data class SkillDto(
    val id: Long? = null,                        // 备份内的旧 id，仅作参考，导入时不复用
    val name: String = "",
    val category: String = "通用",
    val level: Int = 5,
    val note: String? = null,
)

@Serializable
data class GoalDto(
    val id: Long? = null,                        // 备份内的旧 id，导入时重映射
    val parentId: Long? = null,                  // 指向备份内的旧 id，导入时用映射修正
    val level: String = GoalLevels.LONG,
    val title: String = "",
    val category: String = "职业",
    val description: String? = null,
    val metric: String? = null,
    val startDate: String? = null,               // YYYY-MM-DD
    val dueDate: String? = null,                 // YYYY-MM-DD
    val progress: Int = 0,
    val status: String = GoalStatuses.IN_PROGRESS,
    val deletedAt: String? = null,               // ISO 时间戳字符串；非空表示已软删除
)

@Serializable
data class TaskDto(
    val id: Long? = null,                        // 备份内的旧 id，仅作参考
    val goalId: Long? = null,                    // 指向备份内的旧目标 id，导入时重映射
    val date: String = "",                       // YYYY-MM-DD，必填
    val title: String = "",
    val estMinutes: Int = 30,
    val done: Boolean = false,
    val doneAt: String? = null,                  // ISO 时间戳字符串
)

@Serializable
data class DailyLogDto(
    val id: Long? = null,                        // 备份内的旧 id，仅作参考
    val date: String = "",                       // YYYY-MM-DD，必填
    val doneItems: String? = null,
    val studyMinutes: Int = 0,
    val studyNote: String? = null,
    val meals: Map<String, String> = emptyMap(),
    val sleepStart: String? = null,
    val sleepEnd: String? = null,
    val exerciseType: String? = null,
    val exerciseMinutes: Int = 0,
    val mood: Int = 3,
    val energy: Int = 3,
    val reflection: String? = null,
)

@Serializable
data class AiMessageDto(
    val id: Long? = null,                        // 备份内的旧 id，仅作参考
    val mode: String = "",
    val question: String? = null,
    val answer: String = "",
    val source: String = AiSources.AI_MODEL,
    val modelName: String? = null,
    val createdAt: String = "",                  // ISO 时间戳字符串
)
