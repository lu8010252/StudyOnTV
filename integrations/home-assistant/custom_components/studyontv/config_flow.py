"""Config and options flow for the StudyOnTV integration."""

from __future__ import annotations

import logging
from typing import Any

import voluptuous as vol

from homeassistant import config_entries
from homeassistant.const import CONF_HOST, CONF_PORT, CONF_TOKEN
from homeassistant.core import HomeAssistant, callback
from homeassistant.data_entry_flow import FlowResult
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .client import (
    StudyOnTVAuthError,
    StudyOnTVClient,
    StudyOnTVClientError,
    StudyOnTVRateLimitError,
)
from .const import (
    CONF_SCAN_INTERVAL,
    DEFAULT_PORT,
    DEFAULT_SCAN_INTERVAL,
    DOMAIN,
    MAX_SCAN_INTERVAL,
    MIN_SCAN_INTERVAL,
)

_LOGGER = logging.getLogger(__name__)


def _clean_host(value: str) -> str:
    """Accept an IP address or hostname, with or without an http:// prefix."""
    host = value.strip().rstrip("/")
    lowered = host.lower()
    if lowered.startswith("http://"):
        host = host[len("http://") :]
    elif lowered.startswith("https://"):
        host = host[len("https://") :]
    # A path or trailing slash is never part of the device host.
    return host.split("/", 1)[0].strip()


def _clean_token(value: str) -> str:
    """Accept a bare token or a pasted 'Bearer <token>' value."""
    token = value.strip()
    if token.lower().startswith("bearer "):
        token = token[len("bearer ") :].strip()
    return token


STEP_USER_SCHEMA = vol.Schema(
    {
        vol.Required(CONF_HOST): vol.All(cv.string, _clean_host, vol.Length(min=1)),
        vol.Required(CONF_PORT, default=DEFAULT_PORT): cv.port,
        vol.Required(CONF_TOKEN): vol.All(cv.string, _clean_token, vol.Length(min=1)),
    }
)


async def _async_validate_input(
    hass: HomeAssistant, host: str, port: int, token: str
) -> tuple[str | None, dict[str, Any] | None]:
    """Check the token with GET /api/device.

    Returns (error_key, device_payload). error_key is None on success.
    """
    client = StudyOnTVClient(async_get_clientsession(hass), host, port, token)
    try:
        payload = await client.async_get_device()
    except StudyOnTVAuthError:
        return "invalid_auth", None
    except StudyOnTVRateLimitError:
        return "rate_limited", None
    except StudyOnTVClientError as err:
        _LOGGER.debug("Validation of %s failed: %s", client.base_url, err)
        return "cannot_connect", None

    return None, payload


class StudyOnTVConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Handle the initial configuration of StudyOnTV."""

    VERSION = 1

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> FlowResult:
        """Ask for host, port and integration token."""
        errors: dict[str, str] = {}

        if user_input is not None:
            host: str = user_input[CONF_HOST]
            port: int = user_input[CONF_PORT]
            token: str = user_input[CONF_TOKEN]

            error, device = await _async_validate_input(self.hass, host, port, token)
            if error is not None:
                errors["base"] = error
            else:
                await self.async_set_unique_id(f"{host}:{port}")
                self._abort_if_unique_id_configured()
                title = (device or {}).get("name") or f"StudyOnTV ({host})"
                return self.async_create_entry(
                    title=title,
                    data={
                        CONF_HOST: host,
                        CONF_PORT: port,
                        CONF_TOKEN: token,
                    },
                )

        schema = STEP_USER_SCHEMA
        if user_input is not None:
            schema = self.add_suggested_values_to_schema(STEP_USER_SCHEMA, user_input)

        return self.async_show_form(
            step_id="user",
            data_schema=schema,
            errors=errors,
        )

    @staticmethod
    @callback
    def async_get_options_flow(
        config_entry: config_entries.ConfigEntry,
    ) -> StudyOnTVOptionsFlow:
        """Return the options flow."""
        return StudyOnTVOptionsFlow()


class StudyOnTVOptionsFlow(config_entries.OptionsFlow):
    """Change the polling interval."""

    async def async_step_init(
        self, user_input: dict[str, Any] | None = None
    ) -> FlowResult:
        """Manage integration options."""
        if user_input is not None:
            return self.async_create_entry(title="", data=user_input)

        current = self.config_entry.options.get(
            CONF_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL
        )
        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_SCAN_INTERVAL, default=current): vol.All(
                        vol.Coerce(int),
                        vol.Range(min=MIN_SCAN_INTERVAL, max=MAX_SCAN_INTERVAL),
                    ),
                }
            ),
        )
