import os
from pathlib import Path
from dotenv import load_dotenv

# Load .env file if present
env_path = Path(__file__).resolve().parent.parent / ".env"
if env_path.exists():
    load_dotenv(dotenv_path=env_path)


class Settings:
    def __init__(self):
        self.port: int = int(os.getenv("PORT", "8000"))
        self.host: str = os.getenv("HOST", "0.0.0.0")
        self.model_dir: str = os.getenv("MODEL_DIR", "models")
        self.log_level: str = os.getenv("LOG_LEVEL", "INFO")


settings = Settings()
