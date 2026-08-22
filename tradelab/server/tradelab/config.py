from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="TRADELAB_", env_file=".env", extra="ignore")

    data_dir: Path = Path("./data")
    snapshot_keep: int = 15
    snapshot_interval_hours: int = 4
    read_token: str = "change-me"

    @property
    def db_path(self) -> Path:
        return self.data_dir / "tradelab.sqlite3"

    @property
    def snapshot_dir(self) -> Path:
        return self.data_dir / "snapshots"


settings = Settings()
