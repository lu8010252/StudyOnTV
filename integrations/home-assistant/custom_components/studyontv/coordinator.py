"""Data update coordinator for the StudyOnTV integration."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from datetime import timedelta
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PORT, CONF_TOKEN
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .client import StudyOnTVClient, StudyOnTVClientError
from .const import CONF_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL, DOMAIN

_LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class StudyOnTVData:
    """Data returned by the device API."""

    state: dict[str, Any]
    device: dict[str, Any]


class StudyOnTVCoordinator(DataUpdateCoordinator[StudyOnTVData]):
    """Poll /api/state and /api/device on the TV and dispatch remote actions."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        """Initialise the coordinator."""
        self.host: str = entry.data[CONF_HOST]
        self.port: int = entry.data[CONF_PORT]
        self.token: str = entry.data[CONF_TOKEN]
        self.client = StudyOnTVClient(
            async_get_clientsession(hass),
            self.host,
            self.port,
            self.token,
        )

        super().__init__(
            hass,
            _LOGGER,
            config_entry=entry,
            name=DOMAIN,
            update_interval=timedelta(
                seconds=entry.options.get(CONF_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL)
            ),
        )

    @property
    def base_url(self) -> str:
        """Return the base URL of the device API."""
        return self.client.base_url

    async def _async_update_data(self) -> StudyOnTVData:
        """Fetch state and device information."""
        try:
            state, device = await asyncio.gather(
                self.client.async_get_state(),
                self.client.async_get_device(),
            )
        except StudyOnTVClientError as err:
            raise UpdateFailed(str(err)) from err

        return StudyOnTVData(state=state, device=device)

    async def async_remote(self, action: str, minutes: int = 0) -> dict[str, Any]:
        """Execute a remote control command and update coordinator state."""
        result = await self.client.async_remote(action, minutes)
        learning = result.get("learning")
        if self.data is not None and isinstance(learning, dict):
            updated_state = {**self.data.state, "learning": learning}
            self.async_set_updated_data(
                StudyOnTVData(state=updated_state, device=self.data.device)
            )
        await self.async_request_refresh()
        return result
