FROM golang:1.25-alpine AS builder
WORKDIR /build
COPY Tomcats/ .
RUN go build -o tomcats .

FROM alpine:3.21
WORKDIR /app
COPY --from=builder /build/tomcats .
COPY Tomcats/static/ ./static/
ENV STATIC_DIR=/app/static
CMD ["./tomcats"]
