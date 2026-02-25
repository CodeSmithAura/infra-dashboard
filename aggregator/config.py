from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    # Quarkus backend
    BACKEND_URL: str = "http://localhost:8080"

    # How often to pull fresh data (seconds)
    POLL_INTERVAL_SECONDS: int = 30

    # Anomaly detection: z-score threshold
    ANOMALY_ZSCORE_THRESHOLD: float = 2.0

    # SLA tiers
    SLA_GOLD:   float = 99.9
    SLA_SILVER: float = 99.0
    SLA_BRONZE: float = 95.0

settings = Settings()
