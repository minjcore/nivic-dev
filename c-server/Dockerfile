FROM rust:1.75 as builder
WORKDIR /build
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo "fn main() {}" > src/main.rs && cargo build --release
COPY src ./src
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /build/target/release/c-server /usr/local/bin/

ENV C_SERVER_PORT=8080
ENV BLOCKCHAIN_RPC_URL="https://eth-mainnet.g.alchemy.com/v2/demo"
ENV RABBITMQ_URL="amqp://gtel-c-server:password@rabbitmq:5672/gtel-prod"
ENV CUSTODY_WALLET="0x1234567890123456789012345678901234567890"

EXPOSE 8080
ENTRYPOINT ["c-server"]
