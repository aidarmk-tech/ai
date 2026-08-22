from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="TRADELAB_", env_file=".env", extra="ignore")

    data_dir: Path = Path("./data")
    snapshot_keep: int = 15
    snapshot_interval_hours: int = 4
    snapshot_raw_hours: int = 6
    read_token: str = "change-me"

    market_enabled: bool = True
    universe_size: int = 40
    microstructure_size: int = 12
    market_sample_seconds: int = 5
    raw_retention_hours: int = 72
    subscription_refresh_seconds: int = 300
    oi_interval_seconds: int = 60
    max_sample_gap_seconds: int = 12
    paper_exit_grace_seconds: int = 15
    label_grace_seconds: int = 10

    paper_fee_bps_per_side: float = 5.0
    paper_slippage_bps_per_side: float = 2.0
    paper_max_open: int = 2
    paper_starting_notional_usdt: float = 10.0

    @property
    def db_path(self) -> Path:
        return self.data_dir / "tradelab.sqlite3"

    @property
    def snapshot_dir(self) -> Path:
        return self.data_dir / "snapshots"


settings = Settings()
