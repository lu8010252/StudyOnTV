"""Binary sensor platform for the StudyOnTV integration."""

from __future__ import annotations

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN
from .coordinator import StudyOnTVCoordinator
from .sensor import build_device_info


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the StudyOnTV online sensor."""
    coordinator: StudyOnTVCoordinator = hass.data[DOMAIN][entry.entry_id]
    async_add_entities([StudyOnTVOnlineBinarySensor(coordinator, entry)])


class StudyOnTVOnlineBinarySensor(
    CoordinatorEntity[StudyOnTVCoordinator], BinarySensorEntity
):
    """Whether the last poll of the device succeeded."""

    _attr_has_entity_name = True
    _attr_translation_key = "device_online"
    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(
        self, coordinator: StudyOnTVCoordinator, entry: ConfigEntry
    ) -> None:
        """Initialise the binary sensor."""
        super().__init__(coordinator)
        self._attr_unique_id = f"{entry.entry_id}_device_online"
        self._attr_device_info = build_device_info(coordinator, entry)

    @property
    def available(self) -> bool:
        """Stay available while offline so automations can detect the drop."""
        return True

    @property
    def is_on(self) -> bool:
        """Return True when the last poll succeeded."""
        return self.coordinator.last_update_success
