# syntax=docker/dockerfile:1
FROM ghcr.io/astral-sh/uv:0.13.7 AS uv

FROM python:3.13.9-slim-bookworm AS base
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_PYTHON_DOWNLOADS=0 \
    UV_LINK_MODE=copy \
    PATH="/app/.venv/bin:$PATH"
WORKDIR /app
COPY --from=uv /uv /usr/local/bin/uv
COPY pyproject.toml uv.lock ./
COPY app ./app

FROM base AS test
RUN uv sync --locked
COPY tests ./tests
CMD ["python", "-m", "pytest", "-W", "error::DeprecationWarning", "-p", "no:cacheprovider"]

FROM base AS production-build
RUN uv sync --locked --no-dev --no-editable

FROM python:3.13.9-slim-bookworm AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/app/.venv/bin:$PATH"
WORKDIR /app
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
COPY --from=production-build /app/.venv /app/.venv
USER 10001:10001
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["python", "-c", "import json, urllib.request; opener = urllib.request.build_opener(urllib.request.ProxyHandler({})); r = opener.open('http://127.0.0.1:8000/health', timeout=2); assert r.status == 200 and json.load(r) == {'status': 'ok'}"]
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
