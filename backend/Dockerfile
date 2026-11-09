FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt && useradd --create-home --uid 10001 cloudfusion
COPY alembic.ini ./
COPY alembic ./alembic
COPY app ./app
USER cloudfusion
EXPOSE 8000
# Single worker: cloud-to-cloud transfers are in-process and restart-recoverable, not a durable queue.
CMD ["sh", "-c", "alembic upgrade head && exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 1 --proxy-headers"]
