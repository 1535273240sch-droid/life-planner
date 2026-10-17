package com.lifeplanner.core.logic

import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.LifeGap
import kotlin.math.round

/**
 * 缺口检测（D4）：输入数据、输出提醒列表的纯函数。
 * 分类名/标题/文案与 v1 Python 版 services/stats_service.py 一致。
 *
 * @param logs 近 14 天的日志（顺序不限，内部按日期倒序）
 * @param goals 进行中且未删除的目标
 * @param doneTaskCounts 每个目标在近 7 天已完成的任务数，key 为目标 id
 */
object GapDetector {

    private val VEG_KEYWORDS = listOf("菜", "沙拉", "西兰花", "番茄", "黄瓜", "蔬菜", "青菜", "菠菜")
    private val PROTEIN_KEYWORDS = listOf("蛋", "鸡", "鱼", "牛", "肉", "奶", "豆", "虾")

    private val BRACKET_RE = Regex("「(.*?)」|『(.*?)』|【(.*?)】")
    private val BRACKET_CHARS_RE = Regex("[「」『』【】]")
    private val TITLE_PREFIX_RE = Regex("^(攻坚|推进|执行|完成|拆解|阶段[一二三四五六七八九十\\d]+[：:]|学习)")
    private val TITLE_SUFFIX_RE = Regex("(核心里程碑拆解与执行|里程碑|计划|行动|原著精读)$")
    private val ITEM_STEM_RE = Regex("(\\d+分|\\d+级|\\d+|核心|突破|进阶|精通)$")
    private val TITLE_STEM_RE = Regex("(\\d+分|\\d+级|\\d+)$")

    fun detect(logs: List<DailyLog>, goals: List<Goal>, doneTaskCounts: Map<Long, Int>): List<LifeGap> {
        val gaps = ArrayList<LifeGap>()
        val recent = logs.sortedByDescending { it.date }

        // 0. 一条记录都没有：只给系统记录提示（与 v1 Python 的 `if not recent_logs` 一致）
        if (recent.isEmpty()) {
            gaps.add(
                LifeGap(
                    category = "系统记录",
                    level = "注意",
                    title = "连续记录处于初期阶段",
                    detail = "当前系统内记录天数不足3天，建议每天抽出2分钟在「今日记录」填写关键事项，AI将为您激活更深度的多维缺口探测！",
                ),
            )
            return gaps
        }

        val window = recent.take(7)

        // 1. 睡眠缺口
        var missingSleepDays = 0
        var shortSleepDays = 0
        val validSleepHours = ArrayList<Double>()
        for (log in window) {
            val hrs = LogStatsLogic.parseSleepHours(log.sleepStart, log.sleepEnd)
            if (hrs == null) {
                missingSleepDays++
            } else {
                validSleepHours.add(hrs)
                if (hrs < 6.5) shortSleepDays++
            }
        }

        // 1a. 连续多天未记录作息睡眠
        if (window.size >= 3 && missingSleepDays >= 3) {
            gaps.add(
                LifeGap(
                    category = "睡眠与体能",
                    level = "提醒",
                    title = "连续多天未记录作息睡眠 (近7天有${missingSleepDays}天缺省)",
                    detail = "作息规律是高维认知与体能恢复的基石。当前缺失真实入睡与起床时间，系统无法准确评估你的疲劳程度与能量消耗。建议睡前或晨起顺手记录入睡和醒来时间。",
                ),
            )
        }

        // 1b. 持续性睡眠负债（基于有记录的天数）
        if (validSleepHours.isNotEmpty()) {
            val avg = round(validSleepHours.sum() / validSleepHours.size * 10) / 10.0
            if (shortSleepDays >= 3 || avg < 6.8) {
                gaps.add(
                    LifeGap(
                        category = "睡眠与体能",
                        level = "预警",
                        title = "发现持续性睡眠负债 (近7天有${shortSleepDays}天睡眠不足)",
                        detail = "在你记录睡眠的天数中，平均睡眠仅为 $avg 小时（${shortSleepDays}天低于6.5小时）。连续欠觉会导致前额叶皮层认知功能下降15-20%，容易产生代偿性吃糖与注意力涣散。建议今明两晚提早45分钟放下手机就寝。",
                    ),
                )
            }
        }

        // 2. 营养与饮食缺口
        var hasVegCount = 0
        var hasProteinCount = 0
        for (log in window) {
            val mealsText = log.meals.values.joinToString(" ")
            if (VEG_KEYWORDS.any { mealsText.contains(it) }) hasVegCount++
            if (PROTEIN_KEYWORDS.any { mealsText.contains(it) }) hasProteinCount++
        }

        // 与 v1 一致：营养与学习规则要求近 14 天窗口内至少有 3 条记录
        if (recent.size >= 3 && hasVegCount <= 2) {
            gaps.add(
                LifeGap(
                    category = "饮食与营养",
                    level = "提醒",
                    title = "膳食纤维与绿叶蔬菜摄入偏低",
                    detail = "近7天记录中仅有 $hasVegCount 天提到摄入蔬菜类食物。长期缺乏绿叶蔬菜与膳食纤维会导致肠道菌群失衡与午后大脑供能迟缓，建议今日午餐或晚餐主动搭配一份水煮/清炒深色蔬菜。",
                ),
            )
        }

        if (recent.size >= 3 && hasProteinCount <= 2) {
            gaps.add(
                LifeGap(
                    category = "饮食与营养",
                    level = "注意",
                    title = "优质蛋白质摄入频次不足",
                    detail = "近几天饮食多为碳水主食，优质蛋白质摄入频次明显不足。足量蛋白质是维持肌肉量、基础代谢及日间专注度的重要基石，建议早餐增加鸡蛋牛奶，正餐保证手掌大小的瘦肉/豆制品。",
                ),
            )
        }

        // 3. 目标投入断流
        for (goal in goals) {
            val keywords = extractKeywords(goal.title)
            var goalDoneCount = 0
            for (log in window) {
                val corpus = "${log.doneItems ?: ""} ${log.studyNote ?: ""} ${log.reflection ?: ""}"
                if (keywords.any { it.length >= 2 && corpus.contains(it) }) {
                    goalDoneCount++
                }
            }
            val recentTasks = goal.id?.let { doneTaskCounts[it] } ?: 0
            if (goalDoneCount == 0 && recentTasks == 0) {
                gaps.add(
                    LifeGap(
                        category = "目标停滞",
                        level = "预警",
                        title = "目标「${goal.title}」已连续数日零投入",
                        detail = "进行中目标【${goal.title}】在近7天没有任何相关完成事项或任务打卡（当前进度停留在 ${goal.progress}%）。建议将该目标拆出今天1个只需耗时20分钟的最小行动项，先启动动能！",
                    ),
                )
            }
        }

        // 4. 学习时长
        val totalStudy = window.sumOf { it.studyMinutes }
        if (recent.size >= 3 && totalStudy < 120) {
            gaps.add(
                LifeGap(
                    category = "成长与学习",
                    level = "注意",
                    title = "近7天累计学习时长低于预期基线",
                    detail = "最近一周累计深度学习投入仅 $totalStudy 分钟（日均不足20分钟）。建议结合你的价值观约束，在今晚晚饭后划定一段45分钟的不受打扰无手机专注时间槽。",
                ),
            )
        }

        return gaps
    }

    /** 提取目标核心关键词：先取书名号内容并剥离分值/级别后缀，再清洗标题前后缀，标题本身也算。 */
    internal fun extractKeywords(title: String?): List<String> {
        val raw = (title ?: "").trim()
        val keywords = ArrayList<String>()

        for (match in BRACKET_RE.findAll(raw)) {
            for (groupIndex in 1..3) {
                val item = match.groupValues[groupIndex]
                if (item.length >= 2) {
                    keywords.add(item)
                    val stem = ITEM_STEM_RE.replace(item, "").trim()
                    if (stem.length >= 2 && stem !in keywords) keywords.add(stem)
                }
            }
        }

        var cleaned = TITLE_PREFIX_RE.replace(raw, "")
        cleaned = TITLE_SUFFIX_RE.replace(cleaned, "").trim()
        cleaned = BRACKET_CHARS_RE.replace(cleaned, "").trim()
        if (cleaned.length >= 2 && cleaned !in keywords) {
            keywords.add(cleaned)
            val stemClean = TITLE_STEM_RE.replace(cleaned, "").trim()
            if (stemClean.length >= 2 && stemClean !in keywords) keywords.add(stemClean)
        }

        if (raw.isNotEmpty() && raw !in keywords) keywords.add(raw)
        return keywords
    }
}
