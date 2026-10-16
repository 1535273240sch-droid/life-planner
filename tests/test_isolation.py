"""测试隔离自检：保证测试既不会读到开发者的真实 Key，也不会碰真实数据库。"""
import tempfile
from pathlib import Path

import database
from config import settings

PROJECT_ROOT = Path(__file__).resolve().parent.parent


def test_api_key_is_blank_regardless_of_local_env_file():
    assert settings.AI_API_KEY == ""


def test_database_url_points_into_temp_directory_not_project_root():
    db_file = Path(database.engine.url.database).resolve()
    temp_root = Path(tempfile.gettempdir()).resolve()
    assert db_file.name == "test.db"
    assert PROJECT_ROOT not in db_file.parents
    assert db_file != (PROJECT_ROOT / "life_planner.db").resolve()
    assert temp_root in db_file.parents


def test_settings_database_url_matches_engine_file():
    configured = Path(settings.DATABASE_URL.removeprefix("sqlite:///")).resolve()
    assert configured == Path(database.engine.url.database).resolve()


def test_model_name_is_fixed_for_tests():
    assert settings.AI_MODEL == "unit-test-model"


def test_db_fixture_starts_empty_except_default_user(db):
    from models import AiMessage, Goal, Task, User

    assert [u.id for u in db.query(User).all()] == [settings.DEFAULT_USER_ID]
    assert db.query(Goal).count() == 0
    assert db.query(Task).count() == 0
    assert db.query(AiMessage).count() == 0


def test_clean_db_fixture_has_no_users(fresh_db):
    from models import User

    assert fresh_db.query(User).count() == 0


def test_foreign_keys_pragma_is_enabled_on_test_engine(db):
    from sqlalchemy import text

    assert db.execute(text("PRAGMA foreign_keys")).scalar() == 1


def test_data_written_in_one_test_is_gone_in_next_test_part1(client):
    client.post("/api/goals", json={"title": "隔离探针目标"})
    assert len(client.get("/api/goals").json()) == 1


def test_data_written_in_one_test_is_gone_in_next_test_part2(client):
    assert client.get("/api/goals").json() == []
