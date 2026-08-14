FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY sharepoint_login.py ./sharepoint_login.py
ENV DATA_ROOT=/data
ENV PORT=8080
RUN mkdir -p /data/raw /data/protocol
EXPOSE 8080
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8080}"]
