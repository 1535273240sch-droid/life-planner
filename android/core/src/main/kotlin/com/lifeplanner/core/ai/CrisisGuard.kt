package com.lifeplanner.core.ai

import com.lifeplanner.core.model.DailyLog

/**
 * 本地确定性危机筛查：不依赖模型、不依赖网络，命中即短路。
 *
 * 取舍（有意为之）：只做“去空白 + 转小写”后的子串匹配，不做否定句/语境判断。
 * 因此“我不是想死，只是累”“电影里有人自杀”这类句子也会命中。
 * 选择“宁多勿漏”：误报的代价只是多显示一段关怀文字（用户可继续使用），
 * 漏报的代价是生命安全。不要为了降低误报在关键词匹配之外加否定词豁免。
 */
object CrisisGuard {

    /** 危机关键词：照抄 v1 ai_agent._CRISIS_KEYWORDS 的全部词条（含中文扩充与英文）。 */
    val KEYWORDS: List<String> = listOf(
        // 原有
        "不想活", "不想活了", "想死", "想自杀", "自杀", "自残", "割腕", "跳楼", "轻生",
        "结束生命", "活着没意思", "去死", "不想存在", "想结束这一切", "活不下去了", "好绝望不想活",
        // 扩充：生存意愿类
        "活不下去", "不想再活", "活够了", "不如死了", "死了算了", "寻死", "求死", "不想醒来",
        "想消失", "不想继续活", "一了百了", "生无可恋", "想不开", "活着没意义", "寻短见",
        // 扩充：自我终结/自伤类
        "结束自己", "了结自己", "自我了断", "了断自己", "自尽", "上吊", "服毒",
        "伤害自己", "自我伤害", "自伤", "结束自己的生命", "了结生命",
        "自我了结", "了结此生", "了结一切", "以死解脱", "以死明志",
        // 扩充：英文常见表述（去空白后按子串匹配，故 "kill myself" 也会命中）
        "suicide", "killmyself", "wanttodie", "endmylife", "selfharm", "unalivemyself",
    )

    private val WHITESPACE = Regex("\\s+")

    /** 命中返回关怀内容，否则返回 null。空白/空串返回 null。 */
    fun check(text: String?): CrisisPayload? {
        if (text.isNullOrBlank()) return null
        val normalized = WHITESPACE.replace(text, "").lowercase()
        if (KEYWORDS.any { normalized.contains(it) }) return buildPayload()
        return null
    }

    /** 依次检查日志里的自由文本：反思、产出、学习笔记。任一命中即返回。 */
    fun scanLogs(logs: List<DailyLog>): CrisisPayload? {
        for (log in logs) {
            for (field in listOf(log.reflection, log.doneItems, log.studyNote)) {
                check(field)?.let { return it }
            }
        }
        return null
    }

    /** 危机关怀内容（每次返回新对象）。文案照抄 v1 _build_crisis_response。 */
    fun buildPayload(): CrisisPayload = CrisisPayload(
        conclusion = "必须停止规划并寻求专业支持",
        reason = "生命与身心健康是所有人生成长与目标存在的绝对前提。感受到你此刻可能正在承受巨大的心理痛苦与绝望，" +
            "此时任何待办与目标都不及你的生命安危重要。请停下所有规划，给自己一个安全的保护空间。",
        pros = "向外界发出求助是勇敢的自救，能及时获得医学、专业心理咨询与身边人的关怀陪伴，渡过眼前的至暗时刻。",
        consRisks = "独自一人默默承受极度痛苦容易陷入认知隧道与不可逆的高危行动，伤害会永远剥夺未来的一切可能。",
        alternative = "请立即放下手头所有事情，联系你最信任的亲人或朋友陪在身边。并请立即拨打以下 24 小时全国免费专业心理援助热线获取支持：\n" +
            "• 全国希望 24 小时生命救助热线：400-161-9995\n" +
            "• 北京心理危机研究与干预中心热线：010-82951332 或 800-810-1117\n" +
            "• 共青团青少年心理咨询援助热线：12355\n" +
            "• 若情况紧急有即时危险，请立即拨打 110 或 120 寻求保护！",
        goalRelation = "活着才有一切希望。没有任何一项世俗目标值得以生命与健康为代价，请务必珍视自己。",
    )

    /** 危机六段式全文，与 v1 _crisis_full_text 一致。 */
    fun fullText(c: CrisisPayload): String = buildString {
        append("【结论】: ").append(c.conclusion).append("\n\n")
        append("【理由】: ").append(c.reason).append("\n\n")
        append("【好处】: ").append(c.pros).append("\n\n")
        append("【坏处与风险】: ").append(c.consRisks).append("\n\n")
        append("【折中优化方案】: ").append(c.alternative).append("\n\n")
        append("【与目标的关系】: ").append(c.goalRelation)
    }
}
