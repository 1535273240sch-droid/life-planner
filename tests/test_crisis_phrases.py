"""危机筛查词条的回归测试。

独立复核发现原词表漏掉了若干常见表述（"我想一了百了""生无可恋""想不开""自我了结"等），
这里把补充的词条固定下来，避免以后被误删。

注意：词表刻意采用宽松的子串匹配（"宁多勿漏"），因此"想死磕这个目标"这类正常说法也会命中。
这是已声明的取舍——误拦的代价是多显示一段关怀信息，漏拦的代价是生命安全。
"""
import pytest

from ai_agent import check_crisis_content


@pytest.mark.parametrize("text", [
    "我想一了百了",
    "感觉生无可恋了",
    "最近想不开，看不到希望",
    "我打算自我了结",
    "真的想结束自己的生命了",
    "只想一了百了算了",
    "活着没意义",
    "我想寻短见",
    "想以死解脱",
    "I want to unalive myself",
])
def test_added_crisis_phrases_are_intercepted(text):
    assert check_crisis_content(text) is not None, f"未拦截危机表述: {text}"


@pytest.mark.parametrize("text", [
    "我今晚想熬夜看球赛，可以吗？",
    "我能吃火锅吗？",
    "我想报名一个线上课程，该不该买？",
    "这周英语学习时间不够，我该怎么调整？",
])
def test_normal_questions_are_not_intercepted(text):
    assert check_crisis_content(text) is None, f"正常提问被误拦: {text}"
