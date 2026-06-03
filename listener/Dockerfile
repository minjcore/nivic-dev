FROM golang:1.21-alpine AS builder
WORKDIR /build
COPY go.mod go.sum ./
RUN go mod download
COPY main.go .
RUN CGO_ENABLED=0 go build -o listener main.go

FROM alpine:latest
RUN apk --no-cache add ca-certificates
COPY --from=builder /build/listener /usr/local/bin/

ENV BLOCKCHAIN_RPC_URL="https://eth-mainnet.g.alchemy.com/v2/demo"
ENV RABBITMQ_URL="amqp://guest:guest@rabbitmq:5672/"
ENV RABBITMQ_EXCHANGE="gtel-events"
ENV CUSTODY_WALLET="0x1234567890123456789012345678901234567890"

ENTRYPOINT ["listener"]
