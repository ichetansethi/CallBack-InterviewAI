# whisper.cpp HTTP server with the base.en model baked in. CPU only: Docker Desktop's Linux VM
# has no Metal, so this is ~10x slower than running whisper-server on the host.
#   docker build -f docker/whisper.Dockerfile -t callback-whisper .

FROM ubuntu:22.04 AS build
# Exact commit of the local whisper.cpp checkout (git describe: v1.9.3-78-g642b5d32).
ARG WHISPER_COMMIT=642b5d3260e020c2fc6f34a9569d10ddd7672963
ARG WHISPER_MODEL=base.en
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential cmake git ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
# Fetching a single commit by SHA keeps the clone shallow; --branch only takes tags and branches.
RUN git init -q . \
    && git fetch -q --depth 1 https://github.com/ggml-org/whisper.cpp.git "${WHISPER_COMMIT}" \
    && git checkout -q FETCH_HEAD
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
