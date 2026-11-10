FROM python:3.12-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends adb ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/studyontv-watchdog
COPY scripts/device-watchdog.py scripts/device-watchdog.py
COPY deploy/watchdog/run-watchdog.sh deploy/watchdog/run-watchdog.sh
ENV PYTHONUNBUFFERED=1
VOLUME ["/root/.android"]
ENTRYPOINT ["/bin/sh", "/opt/studyontv-watchdog/deploy/watchdog/run-watchdog.sh"]
