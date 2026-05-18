FROM debian:bookworm-slim AS builder
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential libpq-dev libssl-dev && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY saving/ .
RUN make

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq5 libssl3 && rm -rf /var/lib/apt/lists/*
COPY --from=builder /build/saving /app/saving
CMD ["/app/saving"]
