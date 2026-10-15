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

def migrate_db():
    from sqlalchemy import text
    with engine.begin() as conn:
        try:
            res = conn.execute(text("PRAGMA table_info(ai_messages)")).fetchall()
            col_names = [r[1] for r in res]
            if col_names and "source" not in col_names:
                conn.execute(text("ALTER TABLE ai_messages ADD COLUMN source VARCHAR(50) DEFAULT 'ai_model'"))
        except Exception as e:
            pass
