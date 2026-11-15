"""Sensor platform for the StudyOnTV integration."""

from __future__ import annotations

import math
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorEntityDescription,
    SensorStateClass,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import UnitOfTime
from homeassistant.core import HomeAssistant
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity
from homeassistant.util import dt as dt_util

from .const import DOMAIN, REASON_OPTIONS
from .coordinator import StudyOnTVCoordinator, StudyOnTVData


def build_device_info(
    coordinator: StudyOnTVCoordinator, entry: ConfigEntry
) -> DeviceInfo:
    """Build the device registry entry shared by all entities."""
    data = coordinator.data
    device = data.device if data is not None else {}

    info: DeviceInfo = {
        "identifiers": {(DOMAIN, f"{coordinator.host}:{coordinator.port}")},
        "name": device.get("name") or entry.title,
        "manufacturer": "StudyOnTV",
        "model": "StudyOnTV",
        "configuration_url": coordinator.base_url,
    }
    if version := device.get("version"):
        info["sw_version"] = str(version)
    if flavor := device.get("flavor"):
        info["model"] = f"StudyOnTV ({flavor})"
    return info


def _get(data: dict[str, Any], *path: str, default: Any = None) -> Any:
    """Read a nested value without raising on missing or malformed data."""
    current: Any = data
    for key in path:
        if not isinstance(current, dict) or key not in current:
            return default
        current = current[key]
    return default if current is None else current


def _as_float(value: Any, default: float = 0.0) -> float:
    """Coerce an API value to a finite float."""
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return number if math.isfinite(number) else default


def _remaining_minutes(data: StudyOnTVData) -> float | None:
    """learning.remainingSeconds -> minutes.

    The device uses -1 for "unlimited" (an indefinite unlock or a lock without
    an end time). Showing -0.0 minutes would look like an empty quota, so map
    it to unknown instead; the learning_status sensor still shows the reason.
    """
    value = _get(data.state, "learning", "remainingSeconds")
    if value is None:
        return None
    seconds = _as_float(value, default=-1)
    if seconds < 0:
        return None
    return round(seconds / 60, 1)


def _used_minutes_today(data: StudyOnTVData) -> float:
    """today.usedMinutes -> minutes (learning.usedMinutes as fallback)."""
    value = _get(data.state, "today", "usedMinutes")
    if value is None:
        value = _get(data.state, "learning", "usedMinutes")
    return round(_as_float(value), 1)


def _learning_status(data: StudyOnTVData) -> str:
    """learning.reason, with an empty reason mapped to 'available'."""
    reason = _get(data.state, "learning", "reason", default="")
    if not reason:
        return "available"
    return reason if reason in REASON_OPTIONS else "unknown"


def _rounds_today(data: StudyOnTVData) -> int:
    """roundsDoneToday -> completed rounds."""
    value = _get(data.state, "roundsDoneToday")
    if value is None:
        value = _get(data.state, "learning", "roundsDoneToday")
    return int(_as_float(value))


def _question_count(data: StudyOnTVData) -> int:
    """questions -> enabled questions in the bank."""
    return int(_as_float(_get(data.state, "questions")))


def _next_available(data: StudyOnTVData) -> datetime | None:
    """learning.nextAvailableAt (epoch milliseconds) -> datetime."""
    milliseconds = _as_float(_get(data.state, "learning", "nextAvailableAt"))
    if milliseconds <= 0:
        return None
    return dt_util.utc_from_timestamp(milliseconds / 1000)


@dataclass(frozen=True, kw_only=True)
class StudyOnTVSensorEntityDescription(SensorEntityDescription):
    """Describes a StudyOnTV sensor and how to extract its value."""

    value_fn: Callable[[StudyOnTVData], Any]


SENSORS: tuple[StudyOnTVSensorEntityDescription, ...] = (
    StudyOnTVSensorEntityDescription(
        key="remaining_minutes",
        translation_key="remaining_minutes",
        device_class=SensorDeviceClass.DURATION,
        native_unit_of_measurement=UnitOfTime.MINUTES,
        state_class=SensorStateClass.MEASUREMENT,
        icon="mdi:timer-sand",
        value_fn=_remaining_minutes,
    ),
    StudyOnTVSensorEntityDescription(
        key="used_minutes_today",
        translation_key="used_minutes_today",
        device_class=SensorDeviceClass.DURATION,
        native_unit_of_measurement=UnitOfTime.MINUTES,
        state_class=SensorStateClass.MEASUREMENT,
        icon="mdi:timelapse",
        value_fn=_used_minutes_today,
    ),
    StudyOnTVSensorEntityDescription(
        key="learning_status",
        translation_key="learning_status",
        device_class=SensorDeviceClass.ENUM,
        options=list(REASON_OPTIONS),
        icon="mdi:shield-lock-outline",
        value_fn=_learning_status,
    ),
    StudyOnTVSensorEntityDescription(
        key="rounds_today",
        translation_key="rounds_today",
        icon="mdi:repeat-variant",
        value_fn=_rounds_today,
    ),
    StudyOnTVSensorEntityDescription(
        key="question_count",
        translation_key="question_count",
        icon="mdi:book-open-page-variant",
        value_fn=_question_count,
    ),
    StudyOnTVSensorEntityDescription(
        key="next_available",
        translation_key="next_available",
        device_class=SensorDeviceClass.TIMESTAMP,
        icon="mdi:calendar-clock",
        value_fn=_next_available,
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up StudyOnTV sensors."""
    coordinator: StudyOnTVCoordinator = hass.data[DOMAIN][entry.entry_id]
    async_add_entities(
        StudyOnTVSensor(coordinator, entry, description)
        for description in SENSORS
    )


class StudyOnTVSensor(CoordinatorEntity[StudyOnTVCoordinator], SensorEntity):
    """A sensor backed by a field of the device API."""

    _attr_has_entity_name = True
    entity_description: StudyOnTVSensorEntityDescription

    def __init__(
        self,
        coordinator: StudyOnTVCoordinator,
        entry: ConfigEntry,
        description: StudyOnTVSensorEntityDescription,
    ) -> None:
        """Initialise the sensor."""
        super().__init__(coordinator)
        self.entity_description = description
        self._attr_unique_id = f"{entry.entry_id}_{description.key}"
        self._attr_device_info = build_device_info(coordinator, entry)
        if description.options is not None:
            self._attr_options = description.options

    @property
    def native_value(self) -> Any:
        """Return the value converted from the API payload."""
        if self.coordinator.data is None:
            return None
        return self.entity_description.value_fn(self.coordinator.data)
