package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

const (
	exchange     = "saving"
	requestQueue = "topup_requests"
	resultQueue  = "topup_results"
	requestKey   = "topup.requested"
	resultKey    = "topup.result"
)

type TopUpEvent struct {
	TopUpID string `json:"topup_id"`
	UID     uint32 `json:"uid"`
	CardID  string `json:"card_id"`
	Amount  uint64 `json:"amount"`
}

type TopUpResult struct {
	TopUpID string `json:"topup_id"`
	Status  string `json:"status"`
	Reason  string `json:"reason,omitempty"`
}

// ─── Publisher ────────────────────────────────────────────────────────────────

type Publisher struct {
	ch *amqp.Channel
}

func NewPublisher(conn *amqp.Connection) (*Publisher, error) {
	ch, err := conn.Channel()
	if err != nil {
		return nil, err
	}
	if err := declareTopology(ch); err != nil {
		ch.Close()
		return nil, err
	}
	return &Publisher{ch: ch}, nil
}

func (p *Publisher) PublishTopUp(evt TopUpEvent) error {
	body, err := json.Marshal(evt)
	if err != nil {
		return err
	}
	return p.ch.PublishWithContext(context.Background(),
		exchange, requestKey,
		false, false,
		amqp.Publishing{
			ContentType:  "application/json",
			Body:         body,
			DeliveryMode: amqp.Persistent,
			Timestamp:    time.Now(),
		},
	)
}

func (p *Publisher) Close() { p.ch.Close() }

// ─── Result consumer ──────────────────────────────────────────────────────────

// ConsumeResults processes topup.result messages from the Topup Worker
// and updates the DB accordingly. Runs until conn is closed.
func ConsumeResults(conn *amqp.Connection, store *Store) {
	ch, err := conn.Channel()
	if err != nil {
		slog.Error("result consumer channel", "err", err)
		return
	}
	defer ch.Close()

	if err := declareTopology(ch); err != nil {
		slog.Error("result consumer topology", "err", err)
		return
	}
	_ = ch.Qos(1, 0, false)

	msgs, err := ch.Consume(resultQueue, "cards-result-consumer", false, false, false, false, nil)
	if err != nil {
		slog.Error("result consumer consume", "err", err)
		return
	}
	slog.Info("cards result consumer ready", "queue", resultQueue)

	for msg := range msgs {
		var res TopUpResult
		if err := json.Unmarshal(msg.Body, &res); err != nil {
			slog.Warn("bad topup result msg", "err", err)
			msg.Nack(false, false)
			continue
		}

		if res.Status != "done" && res.Status != "failed" {
			res.Status = "failed"
		}
		if err := store.CompleteTopUp(res.TopUpID, res.Status); err != nil {
			slog.Warn("complete topup", "topup_id", res.TopUpID, "err", err)
		} else {
			slog.Info("topup settled", "topup_id", res.TopUpID, "status", res.Status)
		}
		msg.Ack(false)
	}
}

// ─── Topology (shared declaration, idempotent) ────────────────────────────────

func declareTopology(ch *amqp.Channel) error {
	if err := ch.ExchangeDeclare(exchange, "topic", true, false, false, false, nil); err != nil {
		return err
	}
	for _, q := range []string{requestQueue, resultQueue} {
		if _, err := ch.QueueDeclare(q, true, false, false, false, nil); err != nil {
			return err
		}
	}
	if err := ch.QueueBind(requestQueue, requestKey, exchange, false, nil); err != nil {
		return err
	}
	return ch.QueueBind(resultQueue, resultKey, exchange, false, nil)
}
