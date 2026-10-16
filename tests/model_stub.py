"""AI 模型桩：替换 ai_agent._call_model，记录每次调用并按脚本返回文本或抛出 AIError。

契约约定的调用入口签名为 _call_model(messages, temperature) -> str（模型原始文本）。
"""
import copy
import json

HTTP_STATUS_BY_CODE = {
    "ai_not_configured": 503,
    "ai_unreachable": 502,
    "ai_auth_failed": 502,
    "ai_rate_limited": 502,
    "ai_bad_response": 502,
}


def make_ai_error(code, message="测试注入的模型故障"):
    """按契约构造 ai_agent.AIError。其构造签名由 Worker A 决定，这里依次尝试常见写法。"""
    import ai_agent

    error_cls = getattr(ai_agent, "AIError", None)
    if error_cls is None:
        raise AssertionError("ai_agent.AIError 不存在：契约要求用它表示模型调用失败")
    status = HTTP_STATUS_BY_CODE[code]
    attempts = (
        lambda: error_cls(code=code, message=message),
        lambda: error_cls(code, message),
        lambda: error_cls(code=code, message=message, status_code=status),
        lambda: error_cls(code, message, status),
        lambda: error_cls(code),
    )
    last_error = None
    for build in attempts:
        try:
            return build()
        except TypeError as exc:
            last_error = exc
    raise AssertionError(f"无法构造 AIError，请核对其签名: {last_error}")


class FakeModel:
    """可编程的模型桩。

    respond(*payloads)  依次返回给定回复（dict/list 会被序列化成 JSON 字符串，str 原样返回）；
                        只给一条时每次调用都返回它。
    fail(error)         让每次调用抛出异常；error 可以是异常实例，或契约错误码字符串。
    """

    def __init__(self):
        self.calls = []
        self._replies = []
        self._error = None

    def respond(self, *payloads):
        self._error = None
        self._replies = [p if isinstance(p, str) else json.dumps(p, ensure_ascii=False) for p in payloads]
        return self

    def fail(self, error):
        self._error = make_ai_error(error) if isinstance(error, str) else error
        return self

    def __call__(self, messages, temperature=None, *args, **kwargs):
        self.calls.append({"messages": copy.deepcopy(messages), "temperature": temperature})
        if self._error is not None:
            raise self._error
        if not self._replies:
            raise AssertionError("模型桩被调用，但测试没有为它配置任何回复")
        return self._replies.pop(0) if len(self._replies) > 1 else self._replies[0]

    @property
    def call_count(self):
        return len(self.calls)

    @property
    def last_prompt(self):
        """最近一次调用发给模型的全部 message 内容拼成的文本。"""
        assert self.calls, "模型从未被调用"
        return "\n".join(str(m.get("content", "")) for m in self.calls[-1]["messages"])
