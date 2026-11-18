"""HTTP client for the StudyOnTV local device API."""

from __future__ import annotations

from typing import Any

import aiohttp

from .const import API_DEVICE, API_REMOTE, API_STATE, REQUEST_TIMEOUT


class StudyOnTVClientError(Exception):
    """Base error raised by StudyOnTVClient."""


class StudyOnTVAuthError(StudyOnTVClientError):
    """Authentication or authorization error (HTTP 401/403)."""

    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status


class StudyOnTVRateLimitError(StudyOnTVClientError):
    """Rate limit exceeded (HTTP 429)."""


class StudyOnTVConnectionError(StudyOnTVClientError):
    """Network, timeout, or invalid payload error."""


class StudyOnTVClient:
    """Async client for StudyOnTV's LAN HTTP server."""

    def __init__(
        self,
        session: aiohttp.ClientSession,
        host: str,
        port: int,
        token: str,
        timeout: float = REQUEST_TIMEOUT,
    ) -> None:
        """Initialise the client."""
        self._session = session
        self.host = host
        self.port = port
        self.token = token
        self._timeout = aiohttp.ClientTimeout(total=timeout)

    @property
    def base_url(self) -> str:
        """Return the base URL of the device API."""
        return f"http://{self.host}:{self.port}"

    def _headers(self) -> dict[str, str]:
        return {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.token}",
        }

    async def async_get_state(self) -> dict[str, Any]:
        """Fetch GET /api/state."""
        return await self._async_request("GET", API_STATE)

    async def async_get_device(self) -> dict[str, Any]:
        """Fetch GET /api/device."""
        return await self._async_request("GET", API_DEVICE)

    async def async_remote(self, action: str, minutes: int = 0) -> dict[str, Any]:
        """Send a control action via POST /api/remote."""
        body: dict[str, Any] = {"action": action, "minutes": int(minutes)}
        return await self._async_request("POST", API_REMOTE, json_body=body)

    async def _async_request(
        self,
        method: str,
        path: str,
        json_body: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Perform an HTTP request and parse the JSON object response."""
        url = f"{self.base_url}{path}"
        try:
            async with self._session.request(
                method,
                url,
                headers=self._headers(),
                json=json_body,
                timeout=self._timeout,
            ) as response:
                if response.status in (401, 403):
                    if path == API_REMOTE and response.status == 403:
                        raise StudyOnTVAuthError(
                            response.status,
                            "StudyOnTV rejected the control command (403); enable Control permission on the integration token",
                        )
                    raise StudyOnTVAuthError(
                        response.status,
                        "StudyOnTV rejected the integration token (401/403); check that the token is enabled and has required permissions",
                    )
                if response.status == 429:
                    raise StudyOnTVRateLimitError(
                        "StudyOnTV is rate limiting requests (429); try again shortly"
                    )
                if response.status != 200:
                    raise StudyOnTVConnectionError(
                        f"StudyOnTV returned HTTP {response.status} for {path}"
                    )
                payload = await response.json(content_type=None)
        except StudyOnTVClientError:
            raise
        except (aiohttp.ClientError, TimeoutError) as err:
            raise StudyOnTVConnectionError(
                f"Could not reach StudyOnTV at {self.base_url}"
            ) from err
        except ValueError as err:
            raise StudyOnTVConnectionError(
                f"StudyOnTV returned invalid JSON for {path}"
            ) from err

        if not isinstance(payload, dict):
            raise StudyOnTVConnectionError(
                f"StudyOnTV returned unexpected data for {path}"
            )
        return payload
