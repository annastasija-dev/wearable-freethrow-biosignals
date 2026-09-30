from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator

ALLOWED_RAW_STREAMS = frozenset({
    "accelerometer",
    "heart_rate",
    "ppg",
    "eda",
    "skin_temperature",
})


class SessionStartRequest(BaseModel):
    participant_code: str = Field(min_length=1, max_length=32)
    watch_model: str = Field(default="Galaxy Watch", max_length=64)
    wrist: Literal["left", "right"] = "right"
    location: str | None = Field(default=None, max_length=128)
    phone_model: str | None = Field(default=None, max_length=64)
    notes: str | None = Field(default=None, max_length=500)
    weight_kg: float | None = Field(default=None, gt=0, le=300)
    height_cm: int | None = Field(default=None, ge=100, le=250)
    age_years: int | None = Field(default=None, ge=10, le=100)
    sex: str | None = Field(default=None, max_length=64)
    skill_level: str | None = Field(default=None, max_length=64)
    throw_technique: str | None = Field(default=None, max_length=64)


class SessionStartResponse(BaseModel):
    session_id: str
    folder_name: str
    started_at: datetime
    shots_target: int


class ShotLabelRequest(BaseModel):
    result: Literal["hit", "miss"]
    client_timestamp: datetime
    shot_no: int | None = Field(default=None, ge=1, le=20)


class RawImuSample(BaseModel):
    """Legacy accelerometer sample (backward compatible)."""

    time_ms: int
    sensor: Literal["accelerometer", "gyroscope"] = "accelerometer"
    x: float
    y: float
    z: float
    source: str = "samsung_health_sensor"


class RawImuBatchRequest(BaseModel):
    """Legacy batch without stream field."""

    samples: list[RawImuSample]


class RawStreamBatchRequest(BaseModel):
    stream: str = "accelerometer"
    samples: list[dict[str, Any]]

    @field_validator("stream")
    @classmethod
    def normalize_stream(cls, value: str) -> str:
        normalized = value.strip().lower()
        if normalized in {"imu", "accel"}:
            return "accelerometer"
        if normalized not in ALLOWED_RAW_STREAMS:
            raise ValueError(f"Unsupported stream: {value}")
        return normalized

    @field_validator("samples")
    @classmethod
    def require_samples(cls, value: list[dict[str, Any]]) -> list[dict[str, Any]]:
        if not value:
            raise ValueError("samples must not be empty")
        return value


class RawCapabilitiesRequest(BaseModel):
    watch_model: str = Field(default="Galaxy Watch", max_length=64)
    sdk_version: str = Field(default="unknown", max_length=32)
    tracker_mode: str = Field(default="unknown", max_length=32)
    supported_streams: list[str] = Field(default_factory=list)
    active_streams: list[str] = Field(default_factory=list)


class SessionFinishRequest(BaseModel):
    ended_at: datetime | None = None
    notes: str | None = None


class SessionSummary(BaseModel):
    session_id: str
    folder_name: str | None = None
    participant_code: str
    started_at: datetime
    ended_at: datetime | None
    shots_recorded: int
    raw_samples: int
    raw_streams: dict[str, int] = Field(default_factory=dict)
    raw_file: str
    protocol_file: str
    sharepoint_sync: str | None = None
    sharepoint_path: str | None = None
