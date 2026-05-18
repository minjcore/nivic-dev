FROM golang:1.25-alpine AS builder
WORKDIR /build
COPY authservice/ .
RUN go build -o authservice .

FROM alpine:3.21
COPY --from=builder /build/authservice /app/authservice
CMD ["/app/authservice"]
