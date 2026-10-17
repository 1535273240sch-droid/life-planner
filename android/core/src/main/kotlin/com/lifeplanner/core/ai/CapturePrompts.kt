package com.lifeplanner.core.ai

import kotlinx.datetime.LocalDate

/**
 * “一句话整理”的提示词。
 *
 * 设计取舍：
 * - 模型只做“结构化”这一件事。用户没说的字段必须留 null / 空数组，宁缺毋滥：
 *   程序能识别越界数值并裁掉，但“编造出来的内容”无法被程序识别，只能靠提示词约束。
 * - 任务/目标数量上限、目标分类白名单在这里定义，AiService 的校验复用同一份常量，
 *   避免出现“提示词说最多 6 个、代码却按 5 个截断”这种漂移。
 * - 输出格式里把“log 可整体为 null”写成说明性伪 JSON：这段是给模型看的格式说明，
 *   不是可直接解析的 JSON；真正的解析容错由 JsonExtract 负责。
 */
object CapturePrompts {

    /** 单次整理最多接受的今日任务数（提示词与校验共用）。 */
    const val MAX_TASKS = 6

    /** 单次整理最多接受的新目标数（提示词与校验共用）。 */
    const val MAX_GOALS = 2

    /** 目标分类白名单：与 v1 目标分类一致的七个常见分类，模型只能从这里选。 */
    val GOAL_CATEGORIES = listOf("职业", "学习", "健康", "生活", "家庭", "财务", "兴趣")

    /**
     * 一句话整理的用户提示词。
     *
     * @param context 用户画像与最近记录（Prompts.buildContext 的结果）
     * @param rawInput 用户原话
     * @param today 今天日期，供模型判断“今天/明天”的指代
     */
    fun capturePrompt(context: String, rawInput: String, today: LocalDate): String = """
$context

=== 今天日期 ===
$today

=== 用户刚刚说的一句话 ===
"$rawInput"

=== 一句话整理要求 ===
你的任务只有一件：把上面这句话整理成结构化草稿（今日生活记录 / 今日任务 / 想新增的目标），
再用 reply 给用户一句直接回应。请严格遵守：

1. 只整理用户在这句话里**明确说出**的内容。没提到的字段一律填 null（数组则填空数组 []），
   绝对不许编造、不许根据上下文推测补全、不许为了让 JSON 好看而填默认值。
   用户只是随口一问、没有描述生活内容时，log 直接输出 null。
2. log 里没提到的子字段也填 null；meals 只输出用户提到的餐次，没提到的餐次直接省略（不要写空字符串）。
   时间一律用 24 小时制 HH:MM（例如 23:30、07:00），不要写“昨晚”“早上”这类模糊说法。
3. 数值必须是 JSON 整数：study_minutes、exercise_minutes 取 0-1440（分钟）；
   mood、energy 取 1-5 的整数；这些数值没提到时填 null。
4. tasks 最多 $MAX_TASKS 个，title 必须具体可执行且不超过 200 字；同一件事只写一条；
   est_minutes 是 1-1440 的整数，估不出来就填 30。
5. goal_id 只能填写上面“正在推进的重点目标”里出现过的 (ID:n) 数字 n；
   任务不服务于任何已列目标时必须填 null，严禁编造 ID。
6. goals 最多 $MAX_GOALS 个，只在用户明确表达“想开始/打算做某个新目标”时才输出；
   level 必须是 vision / long / stage / weekly 之一（不确定就填 weekly）；
   category 只能从 $GOAL_CATEGORY_TEXT 里选一个；
   description 与 metric 只写用户说到的内容，没说就填 null。
7. reply 是对用户这句话的直接回应（1-3 句）：结合上下文给具体建议或提醒，不要空泛鸡汤，
   不要复述 JSON 内容，也不要声称做了任何落库动作。
8. 只能依据上下文中真实出现的数据作答；上下文没有的信息就明确说“没有记录”。

请严格只输出一个 JSON 对象，不要输出 Markdown 代码围栏、注释或 JSON 之外的任何文字：
{
  "reply": "对这句话的回应或建议",
  "log": {
    "study_minutes": 60,
    "study_note": "看了两章书",
    "sleep_start": "23:30",
    "sleep_end": "07:00",
    "exercise_type": "跑步",
    "exercise_minutes": 30,
    "mood": 4,
    "energy": 3,
    "meals": {"breakfast": "鸡蛋", "lunch": "米饭", "dinner": "面条", "snack": "苹果"},
    "done_items": "完成周报",
    "reflection": "今天状态不错"
  },
  "tasks": [
    {"title": "背 30 个单词", "est_minutes": 30, "goal_id": null}
  ],
  "goals": [
    {"title": "半年内通过六级", "level": "stage", "category": "学习", "description": "每天 1 小时", "metric": "六级 500 分"}
  ]
}
其中 "log" 没有内容时输出 null，"tasks"/"goals" 没有内容时输出 []。
""".trimIndent()

    /** 分类白名单在提示词里的展示文案；单独抽出来避免字符串模板里写复杂表达式。 */
    private val GOAL_CATEGORY_TEXT: String = GOAL_CATEGORIES.joinToString("、")
}
