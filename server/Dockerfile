# Runtime image. Build first with ./build.sh, which creates build/install/handnotes-server.
FROM eclipse-temurin:21-jre
COPY build/install/handnotes-server /app
ENV HANDNOTES_DATA=/data PORT=8787
VOLUME /data
EXPOSE 8787
ENTRYPOINT ["/app/bin/handnotes-server"]
