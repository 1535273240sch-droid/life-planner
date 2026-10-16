"""database.migrate_db：旧版本库升级、幂等、对新库无害。

migrate_db 绑定模块级 engine，所以这里把 engine 临时换成指向手工库的 engine。
"""
import sqlite3

import pytest
from sqlalchemy import create_engine, text

import database

OLD_SCHEMA = """
CREATE TABLE users (id INTEGER PRIMARY KEY, nickname VARCHAR(50), created_at DATETIME);
CREATE TABLE goals (
    id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, parent_id INTEGER, level VARCHAR(20),
    title VARCHAR(200) NOT NULL, category VARCHAR(50), description TEXT, metric VARCHAR(200),
    start_date DATE, due_date DATE, progress INTEGER, status VARCHAR(20)
);
CREATE TABLE ai_messages (
    id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, mode VARCHAR(30) NOT NULL,
    question TEXT, answer TEXT NOT NULL, source VARCHAR(50), created_at DATETIME
);
"""


def _build_old_db(path):
    conn = sqlite3.connect(path)
    conn.executescript(OLD_SCHEMA)
    conn.execute("INSERT INTO users (id, nickname) VALUES (1, '探索者')")
    conn.executemany(
        "INSERT INTO goals (id, user_id, title, level, progress, status) VALUES (?,?,?,?,?,?)",
        [(1, 1, "旧目标一", "long", 30, "in_progress"), (2, 1, "旧目标二", "stage", 100, "completed"), (3, 1, "旧目标三", "weekly", 0, "paused")],
    )
    conn.executemany(
        "INSERT INTO ai_messages (id, user_id, mode, question, answer, source) VALUES (?,?,?,?,?,?)",
        [
            (1, 1, "advisor", "q1", "a1", "ai_model"),
            (2, 1, "advisor", "q2", "a2", "offline_mock"),
            (3, 1, "review", "q3", "a3", None),
            (4, 1, "advisor", "q4", "a4", "crisis_safety_guard"),
        ],
    )
    conn.commit()
    conn.close()


@pytest.fixture
def old_engine(tmp_path, monkeypatch):
    db_path = tmp_path / "old.db"
    _build_old_db(str(db_path))
    url = f"sqlite:///{db_path.as_posix()}"
    engine = create_engine(url, connect_args={"check_same_thread": False})
    monkeypatch.setattr(database, "engine", engine)
    monkeypatch.setattr(database.settings, "DATABASE_URL", url)
    yield engine
    engine.dispose()


def _columns(engine, table):
    with engine.connect() as conn:
        return [row[1] for row in conn.execute(text(f"PRAGMA table_info({table})")).fetchall()]


def _messages(engine):
    with engine.connect() as conn:
        return conn.execute(text("SELECT id, source, model_name FROM ai_messages ORDER BY id")).fetchall()


def _goals(engine):
    with engine.connect() as conn:
        return conn.execute(text("SELECT id, title, level, progress, status, deleted_at FROM goals ORDER BY id")).fetchall()


def test_old_database_really_lacks_new_columns_before_migration(old_engine):
    assert "deleted_at" not in _columns(old_engine, "goals")
    assert "model_name" not in _columns(old_engine, "ai_messages")


def test_migration_adds_goals_deleted_at_column(old_engine):
    database.migrate_db()

    assert "deleted_at" in _columns(old_engine, "goals")


def test_migration_adds_ai_messages_model_name_column(old_engine):
    database.migrate_db()

    assert "model_name" in _columns(old_engine, "ai_messages")


def test_migration_marks_unverifiable_old_messages_as_legacy_unverified(old_engine):
    database.migrate_db()

    sources = {row[0]: row[1] for row in _messages(old_engine)}
    assert sources[1] == "legacy_unverified"
    assert sources[2] == "legacy_unverified"
    assert sources[3] == "legacy_unverified"


def test_migration_does_not_touch_crisis_guard_messages(old_engine):
    database.migrate_db()

    sources = {row[0]: row[1] for row in _messages(old_engine)}
    assert sources[4] == "crisis_safety_guard"


def test_migration_leaves_model_name_empty_for_old_messages(old_engine):
    database.migrate_db()

    assert all(row[2] is None for row in _messages(old_engine))


def test_migration_preserves_goal_data_and_leaves_goals_undeleted(old_engine):
    database.migrate_db()

    rows = _goals(old_engine)
    assert [(r[0], r[1], r[2], r[3], r[4]) for r in rows] == [
        (1, "旧目标一", "long", 30, "in_progress"),
        (2, "旧目标二", "stage", 100, "completed"),
        (3, "旧目标三", "weekly", 0, "paused"),
    ]
    assert all(r[5] is None for r in rows)


def test_migration_is_idempotent_and_does_not_error_on_second_run(old_engine):
    database.migrate_db()
    snapshot = (_goals(old_engine), _messages(old_engine), _columns(old_engine, "goals"), _columns(old_engine, "ai_messages"))

    database.migrate_db()

    assert (_goals(old_engine), _messages(old_engine), _columns(old_engine, "goals"), _columns(old_engine, "ai_messages")) == snapshot


def test_second_migration_does_not_relabel_messages_written_after_first_migration(old_engine):
    database.migrate_db()
    with old_engine.begin() as conn:
        conn.execute(text(
            "INSERT INTO ai_messages (user_id, mode, question, answer, source, model_name) "
            "VALUES (1, 'advisor', 'q5', 'a5', 'ai_model', 'some-model')"
        ))

    database.migrate_db()

    rows = {row[0]: (row[1], row[2]) for row in _messages(old_engine)}
    assert rows[5] == ("ai_model", "some-model")


def test_migration_on_freshly_created_database_is_a_noop(tmp_path, monkeypatch):
    url = f"sqlite:///{(tmp_path / 'fresh.db').as_posix()}"
    engine = create_engine(url, connect_args={"check_same_thread": False})
    monkeypatch.setattr(database, "engine", engine)
    monkeypatch.setattr(database.settings, "DATABASE_URL", url)
    import models  # noqa: F401
    database.Base.metadata.create_all(bind=engine)
    before = (_columns(engine, "goals"), _columns(engine, "ai_messages"))

    database.migrate_db()
    database.migrate_db()

    assert (_columns(engine, "goals"), _columns(engine, "ai_messages")) == before
    engine.dispose()


def test_migration_on_empty_database_without_tables_does_not_error(tmp_path, monkeypatch):
    url = f"sqlite:///{(tmp_path / 'empty.db').as_posix()}"
    engine = create_engine(url, connect_args={"check_same_thread": False})
    monkeypatch.setattr(database, "engine", engine)
    monkeypatch.setattr(database.settings, "DATABASE_URL", url)

    database.migrate_db()

    engine.dispose()


def test_migrated_old_database_is_usable_through_the_orm(old_engine):
    database.migrate_db()
    from sqlalchemy.orm import sessionmaker
    import models

    session = sessionmaker(bind=old_engine)()
    try:
        goals = session.query(models.Goal).filter(models.Goal.deleted_at.is_(None)).all()
        assert [g.title for g in goals] == ["旧目标一", "旧目标二", "旧目标三"]
        assert {m.source for m in session.query(models.AiMessage).all()} == {"legacy_unverified", "crisis_safety_guard"}
    finally:
        session.close()
