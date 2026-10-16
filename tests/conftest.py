"""测试隔离层。

关键约束：database.py 在导入时就用 settings.DATABASE_URL 建 engine，main.py 导入时又会 create_all。
所以下面的环境变量必须在任何项目模块被导入之前设置，因此写在模块级，而不是 fixture 里。
环境变量的优先级高于 .env 文件，显式设成空串即可压过开发者本机 .env 里的真实 Key。
"""
import os
import shutil
import sys
import tempfile
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parent.parent
TESTS_DIR = Path(__file__).resolve().parent

# 项目模块用相对路径挂载 static/ 与 templates/，并且需要能从项目根目录导入
os.chdir(PROJECT_ROOT)
for _path in (str(PROJECT_ROOT), str(TESTS_DIR)):
    if _path not in sys.path:
        sys.path.insert(0, _path)

TEST_TMP_DIR = Path(tempfile.mkdtemp(prefix="life_planner_pytest_")).resolve()
TEST_DB_PATH = TEST_TMP_DIR / "test.db"
TEST_MODEL_NAME = "unit-test-model"

os.environ["DATABASE_URL"] = f"sqlite:///{TEST_DB_PATH.as_posix()}"
os.environ["AI_API_KEY"] = ""
os.environ["AI_MODEL"] = TEST_MODEL_NAME
# 即使某个测试漏打桩，请求也只会打到本机一个无人监听的端口，不会触达任何真实服务商
os.environ["AI_BASE_URL"] = "http://127.0.0.1:9/v1"

import database  # noqa: E402  (必须在上面的环境变量之后导入)
from config import settings  # noqa: E402

REAL_DB_PATH = (PROJECT_ROOT / "life_planner.db").resolve()


def _assert_isolated():
    url = database.engine.url
    db_file = Path(url.database).resolve() if url.database else None
    problems = []
    if url.get_backend_name() != "sqlite" or db_file is None:
        problems.append(f"测试必须使用 SQLite 文件库，当前: {url!r}")
    else:
        if db_file == REAL_DB_PATH or db_file.name == "life_planner.db":
            problems.append(f"测试引擎指向了真实数据库 {db_file}")
        if TEST_TMP_DIR not in db_file.parents:
            problems.append(f"测试数据库 {db_file} 不在临时目录 {TEST_TMP_DIR} 内")
    if problems:
        pytest.exit("测试隔离失效，已中止以保护真实数据: " + "；".join(problems), returncode=3)


def pytest_sessionstart(session):
    _assert_isolated()


def pytest_sessionfinish(session, exitstatus):
    try:
        database.engine.dispose()
    except Exception:
        pass
    shutil.rmtree(TEST_TMP_DIR, ignore_errors=True)


# ---------------------------------------------------------------- 数据库 fixtures

def _reset_database(create_default_user: bool):
    import models  # noqa: F401  确保所有表已注册到 database.Base.metadata

    database.Base.metadata.create_all(bind=database.engine)
    with database.engine.begin() as conn:
        for table in reversed(database.Base.metadata.sorted_tables):
            conn.execute(table.delete())
    if create_default_user:
        from models import User
        session = database.SessionLocal()
        try:
            session.add(User(id=settings.DEFAULT_USER_ID, nickname="探索者"))
            session.commit()
        finally:
            session.close()


@pytest.fixture
def clean_db():
    """全新空库：所有表为空，连默认用户都没有。"""
    _assert_isolated()
    _reset_database(create_default_user=False)
    yield


@pytest.fixture
def fresh_db(clean_db):
    """全新空库上的 Session（没有任何用户）。"""
    session = database.SessionLocal()
    try:
        yield session
    finally:
        session.close()


@pytest.fixture
def db():
    """干净的库 + 默认用户 id=1，返回 Session。"""
    _assert_isolated()
    _reset_database(create_default_user=True)
    session = database.SessionLocal()
    try:
        yield session
    finally:
        session.close()


@pytest.fixture(scope="session")
def app():
    import main
    return main.app


@pytest.fixture
def client(app, db):
    from fastapi.testclient import TestClient
    return TestClient(app)


@pytest.fixture
def fresh_client(app, clean_db):
    """对应全新空库（用户表为空）的客户端，用来回归“从没建过用户就直接请求”的场景。"""
    from fastapi.testclient import TestClient
    return TestClient(app)


# ---------------------------------------------------------------- AI 模型桩

@pytest.fixture
def no_key(monkeypatch):
    """明确的“未配置 Key”状态，不依赖进程环境。"""
    monkeypatch.setattr(settings, "AI_API_KEY", "")


@pytest.fixture
def fake_model(monkeypatch):
    """替换 ai_agent._call_model，并让 Key 被视为已配置。绝不连接真实模型。"""
    import ai_agent
    from model_stub import FakeModel

    stub = FakeModel()
    monkeypatch.setattr(settings, "AI_API_KEY", "test-key")
    monkeypatch.setattr(ai_agent, "_call_model", stub)
    return stub
