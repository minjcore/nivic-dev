package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

// ─── Message types (shared with Cards service) ────────────────────────────────

type TopUpEvent struct {
	TopUpID string `json:"topup_id"`
	UID     uint32 `json:"uid"`
	CardID  string `json:"card_id"`
	Amount  uint64 `json:"amount"`
}

type TopUpResult struct {
	TopUpID string `json:"topup_id"`
	Status  string `json:"status"` // "done" | "failed"
	Reason  string `json:"reason,omitempty"`
}

// ─── Exchange/queue topology (must match Cards service) ───────────────────────

const (
	exchange     = "saving"
	requestQueue = "topup_requests"
	resultQueue  = "topup_results"
	requestKey   = "topup.requested"
	resultKey    = "topup.result"
)

// ─── Worker ───────────────────────────────────────────────────────────────────

type Worker struct {
	ch          *amqp.Channel
	wireHost    string
	wirePort    int
	wireSecret  string
	floatUID    uint32 // VIP account that funds top-ups
	floatPwd    string
}

func setupTopology(ch *amqp.Channel) error {
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

func (w *Worker) Run(ctx context.Context) error {
	if err := w.ch.Qos(1, 0, false); err != nil {
		return err
	}
	msgs, err := w.ch.Consume(requestQueue, "topup-worker", false, false, false, false, nil)
	if err != nil {
		return err
	}
	slog.Info("topup worker listening", "queue", requestQueue)
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case msg, ok := <-msgs:
			if !ok {
				return nil
			}
			w.handle(msg)
		}
	}
}

func (w *Worker) handle(msg amqp.Delivery) {
	var evt TopUpEvent
	if err := json.Unmarshal(msg.Body, &evt); err != nil {
		slog.Warn("bad topup event", "err", err)
		msg.Nack(false, false)
		return
	}
	slog.Info("processing topup", "topup_id", evt.TopUpID, "uid", evt.UID, "amount", evt.Amount)

	result := w.credit(evt)
	w.publishResult(result)
	msg.Ack(false)
}

// credit connects to Wire server as the float account and transfers to the user.
func (w *Worker) credit(evt TopUpEvent) TopUpResult {
	wire, err := Dial(w.wireHost, w.wirePort, w.wireSecret)
	if err != nil {
		return TopUpResult{TopUpID: evt.TopUpID, Status: "failed", Reason: "wire connect: " + err.Error()}
	}
	defer wire.Close()

	token, err := wire.Login(w.floatUID, w.floatPwd)
	if err != nil {
		return TopUpResult{TopUpID: evt.TopUpID, Status: "failed", Reason: "wire login: " + err.Error()}
	}

	if err := wire.Transfer(token, evt.UID, evt.Amount); err != nil {
		return TopUpResult{TopUpID: evt.TopUpID, Status: "failed", Reason: "wire transfer: " + err.Error()}
	}

	slog.Info("wire credit ok", "topup_id", evt.TopUpID, "to", evt.UID, "amount", evt.Amount)
	return TopUpResult{TopUpID: evt.TopUpID, Status: "done"}
}

func (w *Worker) publishResult(res TopUpResult) {
	body, _ := json.Marshal(res)
	err := w.ch.PublishWithContext(
		context.Background(),
		exchange, resultKey,
		false, false,
		amqp.Publishing{
			ContentType:  "application/json",
			Body:         body,
			DeliveryMode: amqp.Persistent,
			Timestamp:    time.Now(),
		},
	)
	if err != nil {
		slog.Error("publish result failed", "err", err)
	}
}
