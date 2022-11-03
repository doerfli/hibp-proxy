FROM adoptopenjdk/openjdk17:alpine AS builder

WORKDIR /build
COPY . .
RUN ./gradlew installDist


# runtime image
FROM adoptopenjdk/openjdk17:alpine

ENV FIREBASE_CREDENTIALS 1234567890
ENV HIBP_API_KEY abcdefgh
EXPOSE 8080

WORKDIR /app
COPY --from=builder /build/build/install/hibp-proxy .

CMD bin/hibp-proxy

