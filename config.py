import os
from pydantic_settings import BaseSettings, SettingsConfigDict
from dotenv import load_dotenv

load_dotenv()

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    APP_HOST: str = os.getenv("APP_HOST", "127.0.0.1")
    APP_PORT: int = int(os.getenv("APP_PORT", "8000"))
    DEBUG: bool = os.getenv("DEBUG", "True").lower() in ("true", "1", "yes")

    DATABASE_URL: str = os.getenv("DATABASE_URL", "sqlite:///./life_planner.db")

    AI_API_KEY: str = os.getenv("AI_API_KEY", "")
    AI_BASE_URL: str = os.getenv("AI_BASE_URL", "https://api.deepseek.com/v1")
    AI_MODEL: str = os.getenv("AI_MODEL", "deepseek-chat")
    AI_MOCK_FALLBACK: bool = os.getenv("AI_MOCK_FALLBACK", "True").lower() in ("true", "1", "yes")

    DEFAULT_USER_ID: int = 1

settings = Settings()
