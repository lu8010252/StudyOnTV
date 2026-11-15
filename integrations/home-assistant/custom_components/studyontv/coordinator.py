"""Data update coordinator for the StudyOnTV integration."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from datetime import timedelta
from typing import Any

import aiohttp

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PORT, CONF_TOKEN
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .const import (
    API_DEVICE,
    API_STATE,
    CONF_SCAN_INTERVAL,
    DEFAULT_SCAN_INTERVAL,
    DOMAIN,
    REQUEST_TIMEOUT,
)

_LOGGER = logging.getLogger(__name__)

_AUTH_FAILED = "StudyOnTV rejected the integration token (401/403); check that the token is enabled and has read permission"
_RATE_LIMITED = "StudyOnTV is rate limiting requests (429); increase the scan interval"
_CANNOT_CONNECT = "Could not reach StudyOnTV at %s"


@dataclass(frozen=True, slots=True)
class StudyOnTVData:
    """Data returned by the device API."""

    state: dict[str, Any]
    device: dict[str, Any]


class StudyOnTVCoordinator(DataUpdateCoordinator[StudyOnTVData]):
    """Poll /api/state and /api/device on the TV."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        """Initialise the coordinator."""
        self.host: str = entry.data[CONF_HOST]
        self.port: int = entry.data[CONF_PORT]
        self.token: str = entry.data[CONF_TOKEN]

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
        return f"http://{self.host}:{self.port}"

    async def _async_update_data(self) -> StudyOnTVData:
        """Fetch state and device information."""
        headers = {"Authorization": f"Bearer {self.token}"}
        session = async_get_clientsession(self.hass)

        state, device = await asyncio.gather(
            self._async_get_json(session, API_STATE, headers),
            self._async_get_json(session, API_DEVICE, headers),
        )
        return StudyOnTVData(state=state, device=device)

    async def _async_get_json(
        self, session: aiohttp.ClientSession, path: str, headers: dict[str, str]
    ) -> dict[str, Any]:
        """GET a JSON object from one device API path."""
        url = f"{self.base_url}{path}"
        try:
            async with session.get(
                url,
                headers=headers,
                timeout=aiohttp.ClientTimeout(total=REQUEST_TIMEOUT),
            ) as response:
                if response.status in (401, 403):
                    raise UpdateFailed(_AUTH_FAILED)
                if response.status == 429:
                    raise UpdateFailed(_RATE_LIMITED)
                if response.status != 200:
                    raise UpdateFailed(
                        f"StudyOnTV returned HTTP {response.status} for {path}"
                    )
                payload = await response.json(content_type=None)
        except UpdateFailed:
            raise
        except (aiohttp.ClientError, TimeoutError) as err:
            raise UpdateFailed(_CANNOT_CONNECT % self.base_url) from err
        except ValueError as err:
            raise UpdateFailed(f"StudyOnTV returned invalid JSON for {path}") from err

        if not isinstance(payload, dict):
            raise UpdateFailed(f"StudyOnTV returned unexpected data for {path}")
        return payload
