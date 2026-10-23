# Runtime image. Build first with ./build.sh, which creates build/install/livequill-server.
FROM eclipse-temurin:21-jre
COPY build/install/livequill-server /app
ENV LIVEQUILL_DATA=/data PORT=8787
VOLUME /data
EXPOSE 8787
ENTRYPOINT ["/app/bin/livequill-server"]
