FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir --requirement requirements.txt

COPY train.py promote.py test_train.py ./

USER 65532:65532

ENTRYPOINT ["python"]
CMD ["/app/train.py"]
