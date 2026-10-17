package com.lifeplanner.core.data

import com.lifeplanner.core.db.LifePlannerDb
import com.lifeplanner.core.model.Profile
import com.lifeplanner.core.port.ProfileRepository
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 档案仓储实现（单用户，库里至多一行）。
 * 全部方法同步执行；health_info 在库中是 JSON 文本，仓储层负责与 Map 互转。
 * 注意：SQLDelight 生成的属性名沿用库里的列名（不转驼峰），故读行时用 health_info 等原名。
 */
class ProfileRepositoryImpl(private val db: LifePlannerDb) : ProfileRepository {

    private val q get() = db.queriesQueries

    override fun get(): Profile? =
        q.selectProfile().executeAsOneOrNull()?.let { row ->
            Profile(
                id = row.id,
                age = row.age?.toInt(),
                gender = row.gender,
                occupation = row.occupation,
                industry = row.industry,
                yearsOfWork = row.years_of_work?.toInt(),
                education = row.education,
                major = row.major,
                healthInfo = RepoJsonMaps.decode(row.health_info),
                vision = row.vision,
                constraints = row.constraints,
                updatedAt = row.updated_at,
            )
        }

    override fun save(profile: Profile): Profile {
        val existingId = q.selectProfileId().executeAsOneOrNull()
        val targetId = profile.id ?: existingId
        db.transaction {
            if (targetId != null) {
                // 单行：先清空再按原 id 写回，确保库里始终只有一行
                q.deleteAllProfiles()
                q.insertProfileWithId(
                    targetId, profile.age?.toLong(), profile.gender, profile.occupation,
                    profile.industry, profile.yearsOfWork?.toLong(), profile.education, profile.major,
                    RepoJsonMaps.encode(profile.healthInfo), profile.vision, profile.constraints,
                    profile.updatedAt,
                )
            } else {
                q.insertProfile(
                    profile.age?.toLong(), profile.gender, profile.occupation,
                    profile.industry, profile.yearsOfWork?.toLong(), profile.education, profile.major,
                    RepoJsonMaps.encode(profile.healthInfo), profile.vision, profile.constraints,
                    profile.updatedAt,
                )
            }
        }
        // 读回真实落库结果（含自增 id）
        return get() ?: profile.copy(id = targetId)
    }
}

/**
 * 仓储层共用的 JSON 文本 ↔ Map<String,String> 转换。
 * meals / health_info 都是自由键值，用 kotlinx.serialization 处理转义；
 * 解析失败一律退化为空 Map，不抛异常。
 */
internal object RepoJsonMaps {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(map: Map<String, String>): String =
        try {
            json.encodeToString(map)
        } catch (e: Exception) {
            "{}"
        }

    fun decode(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, String>>(text)
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
