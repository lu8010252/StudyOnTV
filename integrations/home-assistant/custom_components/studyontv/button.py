"""Button platform for the StudyOnTV integration."""

from __future__ import annotations

from dataclasses import dataclass

from homeassistant.components.button import ButtonEntity, ButtonEntityDescription
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .client import StudyOnTVClientError
from .const import DOMAIN
from .coordinator import StudyOnTVCoordinator
from .sensor import build_device_info


@dataclass(frozen=True, kw_only=True)
class StudyOnTVButtonEntityDescription(ButtonEntityDescription):
    """Describes a StudyOnTV remote control button."""

    action: str
    minutes: int = 0


BUTTONS: tuple[StudyOnTVButtonEntityDescription, ...] = (
    StudyOnTVButtonEntityDescription(
        key="unlock_30m",
        translation_key="unlock_30m",
        icon="mdi:lock-open-outline",
        action="unlock",
        minutes=30,
    ),
    StudyOnTVButtonEntityDescription(
        key="lock_now",
        translation_key="lock_now",
        icon="mdi:lock-outline",
        action="lock",
        minutes=0,
    ),
    StudyOnTVButtonEntityDescription(
        key="cancel_override",
        translation_key="cancel_override",
        icon="mdi:restore",
        action="cancel",
        minutes=0,
    ),
    StudyOnTVButtonEntityDescription(
        key="credit_add_10m",
        translation_key="credit_add_10m",
        icon="mdi:clock-plus-outline",
        action="credit",
        minutes=10,
    ),
    StudyOnTVButtonEntityDescription(
        key="credit_sub_10m",
        translation_key="credit_sub_10m",
        icon="mdi:clock-minus-outline",
        action="credit",
        minutes=-10,
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up StudyOnTV control buttons."""
    coordinator: StudyOnTVCoordinator = hass.data[DOMAIN][entry.entry_id]
    async_add_entities(
        StudyOnTVButton(coordinator, entry, description)
        for description in BUTTONS
    )


class StudyOnTVButton(CoordinatorEntity[StudyOnTVCoordinator], ButtonEntity):
    """A button that triggers a POST /api/remote control action."""

    _attr_has_entity_name = True
    entity_description: StudyOnTVButtonEntityDescription

    def __init__(
        self,
        coordinator: StudyOnTVCoordinator,
        entry: ConfigEntry,
        description: StudyOnTVButtonEntityDescription,
    ) -> None:
        """Initialise the control button."""
        super().__init__(coordinator)
        self.entity_description = description
        self._attr_unique_id = f"{entry.entry_id}_{description.key}"
        self._attr_device_info = build_device_info(coordinator, entry)

    async def async_press(self) -> None:
        """Send the remote action to StudyOnTV."""
        try:
            await self.coordinator.async_remote(
                self.entity_description.action,
                self.entity_description.minutes,
            )
        except StudyOnTVClientError as err:
            raise HomeAssistantError(str(err)) from err
