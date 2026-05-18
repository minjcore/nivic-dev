#!/usr/bin/env python3
"""
Test Tomcats push notification end-to-end.
Usage:
  python3 test_tomcats.py <uid> [amount]

Steps:
  1. Register a test APNs token via POST /tokens  (skip if you already did)
  2. Publish a topup.result event to RabbitMQ
"""
import sys, json, pika, requests

TOMCATS_URL = "http://localhost:8093"
AMQP_URL    = "amqp://guest:guest@localhost:5672/"
EXCHANGE    = "saving"

uid    = int(sys.argv[1]) if len(sys.argv) > 1 else 16777216
amount = int(sys.argv[2]) if len(sys.argv) > 2 else 500_000

# ── publish topup.result ──────────────────────────────────────────────────────
conn   = pika.BlockingConnection(pika.URLParameters(AMQP_URL))
ch     = conn.channel()
ch.exchange_declare(EXCHANGE, exchange_type="topic", durable=True)

payload = json.dumps({
    "topup_id": "test-001",
    "uid":      uid,
    "amount":   amount,
    "status":   "done",
}).encode()

ch.basic_publish(
    exchange    = EXCHANGE,
    routing_key = "topup.result",
    body        = payload,
    properties  = pika.BasicProperties(delivery_mode=2),
)
conn.close()
print(f"[ok] published topup.result uid={uid} amount={amount:,}")
print("     watch Tomcats logs for push dispatch")
