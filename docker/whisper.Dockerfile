# whisper.cpp HTTP server with the base.en model baked in. CPU only: Docker Desktop's Linux VM
# has no Metal, so this is ~10x slower than running whisper-server on the host.
#   docker build -f docker/whisper.Dockerfile -t callback-whisper .

FROM ubuntu:22.04 AS build
ARG WHISPER_REF=v1.9.3
ARG WHISPER_MODEL=base.en
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential cmake git ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN git clone --depth 1 --branch "${WHISPER_REF}" https://github.com/ggml-org/whisper.cpp.git .
# Static, portable build (no -march=native) so the image runs on any CPU the VM exposes.
RUN cmake -B build -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DBUILD_SHARED_LIBS=OFF \
    && cmake --build build -j --target whisper-server
RUN mkdir /models && sh models/download-ggml-model.sh "${WHISPER_MODEL}" /models

FROM ubuntu:22.04
ARG WHISPER_MODEL=base.en
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/build/bin/whisper-server /usr/local/bin/whisper-server
COPY --from=build /models/ggml-${WHISPER_MODEL}.bin /models/model.bin
EXPOSE 8090
# voice-orchestrator expects :8090 (whisper-server's own default is 8080).
ENTRYPOINT ["whisper-server", "--host", "0.0.0.0", "--port", "8090", "-m", "/models/model.bin"]
