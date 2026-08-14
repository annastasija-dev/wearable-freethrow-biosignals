from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    data_root: Path = Path(__file__).resolve().parents[1] / "data"
    api_key: str = "dev-change-me"
    cors_origins: str = "*"
    max_shots_per_session: int = 10

    # SharePoint / OneDrive (Microsoft Graph)
    sharepoint_enabled: bool = True
    sharepoint_folder: str = "2026 Shooting data Samsung/Baudu metimu duomenys/results"
    sharepoint_local_path: Path | None = None  # optional local OneDrive sync folder
    graph_client_id: str = ""
    graph_tenant_id: str = "vgtuitsc.onmicrosoft.com"
    graph_token_cache: Path = Path(__file__).resolve().parents[1] / ".token_cache.json"
    # Optional: paste refresh token for always-on hosts (preferred over interactive login)
    graph_refresh_token: str = ""

    @property
    def raw_root(self) -> Path:
        return self.data_root / "raw"

    @property
    def protocol_root(self) -> Path:
        return self.data_root / "protocol"


settings = Settings()
settings.raw_root.mkdir(parents=True, exist_ok=True)
settings.protocol_root.mkdir(parents=True, exist_ok=True)
