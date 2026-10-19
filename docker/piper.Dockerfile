# Piper TTS served by piper's own HTTP server: POST /synthesize {"text": "..."} -> WAV bytes,
# which is the contract voice-orchestrator's PiperClient uses.
#   docker build -f docker/piper.Dockerfile -t callback-piper .

FROM python:3.12-slim
ARG PIPER_VERSION=1.8.0
ARG PIPER_VOICE=en_US-lessac-medium
RUN pip install --no-cache-dir "piper-tts[http]==${PIPER_VERSION}"
WORKDIR /voices
RUN python -m piper.download_voices --download-dir /voices "${PIPER_VOICE}"
ENV PIPER_VOICE=${PIPER_VOICE}
EXPOSE 8091
# The server's own default port is 5000; voice-orchestrator expects 8091.
CMD ["sh", "-c", "exec python -m piper.http_server --host 0.0.0.0 --port 8091 -m /voices/${PIPER_VOICE}.onnx"]
