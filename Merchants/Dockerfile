FROM golang:1.22-alpine AS builder
WORKDIR /build
COPY Merchants/ .
RUN go build -o merchants .

FROM alpine:3.21
WORKDIR /app
COPY --from=builder /build/merchants .
ENV MERCHANTS_ADDR=:8090
CMD ["./merchants"]
