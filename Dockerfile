# UnisonServer image, built from a plain clone with nothing but Docker:
#
#   docker build -t unisonserver:local .
#
# The build stages run on the machine doing the build; only the last stage is for the target platform, so
# `docker buildx build --platform linux/amd64,linux/arm64 .` compiles once. The distribution is jars, and the
# ONNX Runtime and SQLite jars carry their native libraries for both x86-64 and ARM64.

# Third-party sources the build needs: the reader's foliate-js at a pinned commit, and the speech model,
# checked against its SHA-256 (the build fails when it does not match).
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-jammy AS fetch
RUN apt-get update && apt-get install -y --no-install-recommends git curl ca-certificates && rm -rf /var/lib/apt/lists/*
ARG FOLIATE_COMMIT=78914aef4466eb960965702401634c2cb348e9b1
ARG MODEL_URL=https://github.com/Natsume-197/Honomiya/releases/download/models-v1/quartznet15x5-en.model.int8.onnx
ARG MODEL_SHA256=98c3918f0c5ee620e9fc894cafc50cbb925bdddb1f153c959c1568d2319228ba
WORKDIR /fetch
RUN git init foliate-js \
    && git -C foliate-js remote add origin https://github.com/johnfactotum/foliate-js.git \
    && git -C foliate-js fetch --depth 1 origin "$FOLIATE_COMMIT" \
    && git -C foliate-js checkout --detach FETCH_HEAD \
    && test "$(git -C foliate-js rev-parse HEAD)" = "$FOLIATE_COMMIT"
RUN curl -fL --retry 3 -o model.onnx "$MODEL_URL" \
    && echo "$MODEL_SHA256  model.onnx" | sha256sum -c -

# The server distribution. No Android SDK is needed for the :server module.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
COPY . .
COPY --from=fetch /fetch/foliate-js vendor/foliate-js
# Run through sh: a checkout made on Windows does not carry the wrapper's executable bit.
RUN sh ./gradlew --configure-on-demand --no-daemon --console=plain :server:installDist

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/server/build/install/server/ /app/
RUN chmod +x /app/bin/server
COPY --from=fetch /fetch/model.onnx /app/model.onnx
ENV UNISON_MODEL=/app/model.onnx
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
