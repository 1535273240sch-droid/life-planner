from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker, declarative_base
from sqlite3 import Connection as SQLite3Connection
from config import settings

engine_args = {}
if settings.DATABASE_URL.startswith("sqlite"):
    engine_args["connect_args"] = {"check_same_thread": False}

engine = create_engine(
    settings.DATABASE_URL,
    **engine_args
)

if settings.DATABASE_URL.startswith("sqlite"):
    @event.listens_for(engine, "connect")
    def set_sqlite_pragma(dbapi_connection, connection_record):
        if isinstance(dbapi_connection, SQLite3Connection):
            cursor = dbapi_connection.cursor()
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.close()

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

def _column_names(conn, table: str):
    from sqlalchemy import text
    # table 只会是本文件内的字面量，不接收外部输入
    return [row[1] for row in conn.execute(text(f"PRAGMA table_info({table})")).fetchall()]


def migrate_db():
    """SQLite 增量迁移。幂等：对已迁移的库重复执行不会有任何改动。"""
    from sqlalchemy import text
    if not settings.DATABASE_URL.startswith("sqlite"):
        return
    with engine.begin() as conn:
        goal_cols = _column_names(conn, "goals")
        if goal_cols and "deleted_at" not in goal_cols:
            conn.execute(text("ALTER TABLE goals ADD COLUMN deleted_at DATETIME"))

        msg_cols = _column_names(conn, "ai_messages")
        if msg_cols:
            if "source" not in msg_cols:
                conn.execute(text("ALTER TABLE ai_messages ADD COLUMN source VARCHAR(50)"))
            if "model_name" not in msg_cols:
                conn.execute(text("ALTER TABLE ai_messages ADD COLUMN model_name VARCHAR(100)"))
                # model_name 列出现之前，source 只是列默认值，并不记录真实来源（模板输出也被标成 ai_model）。
                # 这些旧记录无法核实，统一标记，避免继续被当作真实 AI 输出展示。
                conn.execute(text(
                    "UPDATE ai_messages SET source='legacy_unverified' "
                    "WHERE source IS NULL OR source IN ('ai_model', 'offline_mock')"
                ))
