package com.lifeplanner.core.data

import com.lifeplanner.core.db.LifePlannerDb
import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.port.DailyLogRepository
import com.lifeplanner.core.util.Dates
import kotlinx.datetime.LocalDate

/** 每日记录仓储实现：按 date 一天一条；meals 在库中是 JSON 文本，仓储层与 Map 互转。 */
class DailyLogRepositoryImpl(private val db: LifePlannerDb) : DailyLogRepository {

    private val q get() = db.queriesQueries

    // 生成的行属性沿用库列名：done_items / study_minutes / sleep_start / exercise_minutes。

    override fun findByDate(date: LocalDate): DailyLog? =
        q.selectDailyLogByDate(date.toString()).executeAsOneOrNull()?.let { row ->
            val d = Dates.parse(row.date) ?: return@let null
            DailyLog(
                id = row.id,
                date = d,
                doneItems = row.done_items,
                studyMinutes = row.study_minutes.toInt(),
                studyNote = row.study_note,
                meals = RepoJsonMaps.decode(row.meals),
                sleepStart = row.sleep_start,
                sleepEnd = row.sleep_end,
                exerciseType = row.exercise_type,
                exerciseMinutes = row.exercise_minutes.toInt(),
                mood = row.mood.toInt(),
                energy = row.energy.toInt(),
                reflection = row.reflection,
            )
        }

    override fun listBetween(from: LocalDate, to: LocalDate): List<DailyLog> =
        q.selectDailyLogsBetween(from.toString(), to.toString()).executeAsList().mapNotNull { row ->
            val d = Dates.parse(row.date) ?: return@mapNotNull null
            DailyLog(
                id = row.id,
                date = d,
                doneItems = row.done_items,
                studyMinutes = row.study_minutes.toInt(),
                studyNote = row.study_note,
                meals = RepoJsonMaps.decode(row.meals),
                sleepStart = row.sleep_start,
                sleepEnd = row.sleep_end,
                exerciseType = row.exercise_type,
                exerciseMinutes = row.exercise_minutes.toInt(),
                mood = row.mood.toInt(),
                energy = row.energy.toInt(),
                reflection = row.reflection,
            )
        }

    override fun listAll(): List<DailyLog> =
        q.selectAllDailyLogs().executeAsList().mapNotNull { row ->
            val d = Dates.parse(row.date) ?: return@mapNotNull null
            DailyLog(
                id = row.id,
                date = d,
                doneItems = row.done_items,
                studyMinutes = row.study_minutes.toInt(),
                studyNote = row.study_note,
                meals = RepoJsonMaps.decode(row.meals),
                sleepStart = row.sleep_start,
                sleepEnd = row.sleep_end,
                exerciseType = row.exercise_type,
                exerciseMinutes = row.exercise_minutes.toInt(),
                mood = row.mood.toInt(),
                energy = row.energy.toInt(),
                reflection = row.reflection,
            )
        }

    override fun upsert(log: DailyLog): DailyLog {
        // 两步写法（select → insert/update），整段放事务里避免竞态。
        // 不用 ON CONFLICT：安卓 API 24-29 自带 SQLite 低于 3.24，真机会语法报错。
        db.transaction {
            val exists = q.selectDailyLogByDate(log.date.toString()).executeAsOneOrNull() != null
            if (exists) {
                q.updateDailyLogByDate(
                    log.doneItems, log.studyMinutes.toLong(), log.studyNote,
                    RepoJsonMaps.encode(log.meals), log.sleepStart, log.sleepEnd, log.exerciseType,
                    log.exerciseMinutes.toLong(), log.mood.toLong(), log.energy.toLong(),
                    log.reflection, log.date.toString(),
                )
            } else {
                q.insertDailyLog(
                    log.date.toString(), log.doneItems, log.studyMinutes.toLong(), log.studyNote,
                    RepoJsonMaps.encode(log.meals), log.sleepStart, log.sleepEnd, log.exerciseType,
                    log.exerciseMinutes.toLong(), log.mood.toLong(), log.energy.toLong(), log.reflection,
                )
            }
        }
        // 读回真实落库结果（含 id）
        return findByDate(log.date) ?: log
    }

    override fun replaceAll(logs: List<DailyLog>) {
        // 先清空：空列表也必须清表（导入 REPLACE 依赖此语义）
        db.transaction {
            q.deleteAllDailyLogs()
            for (log in logs) {
                val date = log.date.toString()
                if (log.id != null) {
                    q.insertDailyLogWithId(
                        log.id, date, log.doneItems, log.studyMinutes.toLong(), log.studyNote,
                        RepoJsonMaps.encode(log.meals), log.sleepStart, log.sleepEnd, log.exerciseType,
                        log.exerciseMinutes.toLong(), log.mood.toLong(), log.energy.toLong(), log.reflection,
                    )
                } else {
                    q.insertDailyLog(
                        date, log.doneItems, log.studyMinutes.toLong(), log.studyNote,
                        RepoJsonMaps.encode(log.meals), log.sleepStart, log.sleepEnd, log.exerciseType,
                        log.exerciseMinutes.toLong(), log.mood.toLong(), log.energy.toLong(), log.reflection,
                    )
                }
            }
        }
    }
}
