FROM adoptopenjdk/openjdk11:alpine AS builder

WORKDIR /build
COPY . .
RUN ./gradlew installDist


# runtime image
FROM adoptopenjdk/openjdk11:alpine

ENV FIREBASE_CREDENTIALS 1234567890
ENV HIBP_API_KEY abcdefgh

WORKDIR /app
COPY --from=builder /build/build/install/hibp-proxy .

CMD bin/hibp-proxy

