# UnisonServer image: a JRE, ffmpeg, the built server distribution and the speech model.
# The distribution is built on a development machine (`gradlew :server:installDist`) and copied in;
# nothing is compiled here, so the Docker host needs no JDK or Android SDK.
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
COPY install/ /app/
# The distribution is packaged on Windows, where the start script's executable bit is lost.
RUN chmod +x /app/bin/server
COPY model.onnx /app/model.onnx
ENV UNISON_MODEL=/app/model.onnx
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
