# Stage 1: build the Hebrew receipt font from Noto fonts
FROM python:3.12-slim AS fonts
RUN apt-get update && apt-get install -y --no-install-recommends fonts-noto-core \
    && rm -rf /var/lib/apt/lists/* && pip install --no-cache-dir fonttools
COPY app/build_fonts.py /build/build_fonts.py
RUN mkdir -p /build/out && python -c "import sys; sys.path.insert(0,'/build'); \
from pathlib import Path; import build_fonts; build_fonts.build(out_dir=Path('/build/out'))"

# Stage 2: the bot
FROM python:3.12-slim
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY --from=fonts /build/out/ ./app/fonts/
ENV DATA_DIR=/data
EXPOSE 8000
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
