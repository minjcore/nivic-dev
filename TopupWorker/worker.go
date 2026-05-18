package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

const (
	maxAttempts    = 4
	retryBaseDelay = time.Second
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
	UID     uint32 `json:"uid"`
	Amount  uint64 `json:"amount"`
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

	result := w.creditWithRetry(evt)
	w.publishResult(result)
	msg.Ack(false)
}

// creditWithRetry attempts the Wire credit up to maxAttempts times with
// exponential backoff. Permanent errors (low balance, bad account) fail fast.
func (w *Worker) creditWithRetry(evt TopUpEvent) TopUpResult {
	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		err := w.credit(evt)
		if err == nil {
			if attempt > 1 {
				slog.Info("wire credit ok after retry",
					"topup_id", evt.TopUpID, "attempt", attempt)
			} else {
				slog.Info("wire credit ok", "topup_id", evt.TopUpID,
					"to", evt.UID, "amount", evt.Amount)
			}
			return TopUpResult{TopUpID: evt.TopUpID, UID: evt.UID, Amount: evt.Amount, Status: "done"}
		}

		lastErr = err
		if isPermanent(err) {
			slog.Error("topup permanent failure, not retrying",
				"topup_id", evt.TopUpID, "err", err)
			break
		}

		if attempt < maxAttempts {
			delay := retryBaseDelay * (1 << (attempt - 1)) // 1s, 2s, 4s
			slog.Warn("topup transient error, retrying",
				"topup_id", evt.TopUpID, "attempt", attempt,
				"next_in", delay, "err", err)
			time.Sleep(delay)
		}
	}

	slog.Error("topup failed after all attempts",
		"topup_id", evt.TopUpID, "attempts", maxAttempts, "err", lastErr)
	return TopUpResult{TopUpID: evt.TopUpID, UID: evt.UID, Amount: evt.Amount, Status: "failed", Reason: lastErr.Error()}
}

// credit makes one attempt to connect to Wire and credit the user via CASH_IN.
// Returns nil on success, error on any failure.
func (w *Worker) credit(evt TopUpEvent) error {
	wire, err := Dial(w.wireHost, w.wirePort, w.wireSecret)
	if err != nil {
		return fmt.Errorf("wire connect: %w", err)
	}
	defer wire.Close()

	token, err := wire.Login(w.floatUID, w.floatPwd)
	if err != nil {
		return fmt.Errorf("wire login: %w", err)
	}

	if err := wire.CashIn(token, evt.UID, evt.Amount, evt.TopUpID); err != nil {
		return fmt.Errorf("wire cash_in: %w", err)
	}
	return nil
}

// isPermanent returns true for errors that won't resolve with a retry.
var permanentPhrases = []string{
	"low balance", "bad token", "not found", "bad password", "id reserved",
}

func isPermanent(err error) bool {
	if err == nil {
		return false
	}
	msg := strings.ToLower(err.Error())
	for _, p := range permanentPhrases {
		if strings.Contains(msg, p) {
			return true
		}
	}
	return false
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
