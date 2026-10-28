FROM python:3.12-slim
WORKDIR /service
COPY requirements.lock .
RUN pip install --no-cache-dir -r requirements.lock
COPY app ./app
RUN useradd --create-home service
USER service
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
