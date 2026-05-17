package main

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"

	amqp "github.com/rabbitmq/amqp091-go"
)

const (
	exchange     = "saving"
	tomcatsQueue = "tomcats_events"
)

type TopUpResult struct {
	TopUpID string `json:"topup_id"`
	UID     uint32 `json:"uid"`
	Amount  uint64 `json:"amount"`
	Status  string `json:"status"` // "done" | "failed"
	Reason  string `json:"reason,omitempty"`
}

func ConsumeEvents(conn *amqp.Connection, store *Store, apns *APNsClient, fcm *FCMClient) {
	ch, err := conn.Channel()
	if err != nil {
		slog.Error("consumer channel", "err", err)
		return
	}
	defer ch.Close()

	if err := setupTopology(ch); err != nil {
		slog.Error("consumer topology", "err", err)
		return
	}
	_ = ch.Qos(1, 0, false)

	msgs, err := ch.Consume(tomcatsQueue, "tomcats", false, false, false, false, nil)
	if err != nil {
		slog.Error("consume", "err", err)
		return
	}
	slog.Info("tomcats consumer ready", "queue", tomcatsQueue)

	for msg := range msgs {
		handleMsg(msg, store, apns, fcm)
	}
}

func handleMsg(msg amqp.Delivery, store *Store, apns *APNsClient, fcm *FCMClient) {
	switch msg.RoutingKey {
	case "topup.result":
		var res TopUpResult
		if err := json.Unmarshal(msg.Body, &res); err != nil {
			slog.Warn("bad topup result", "err", err)
			msg.Nack(false, false)
			return
		}
		if res.Status == "done" && res.UID != 0 {
			go dispatch(store, apns, fcm, res.UID,
				"Nạp tiền thành công",
				fmt.Sprintf("+%s đ vào tài khoản", fmtVND(res.Amount)))
		}
	default:
		slog.Debug("unknown routing key", "key", msg.RoutingKey)
	}
	msg.Ack(false)
}

func dispatch(store *Store, apns *APNsClient, fcm *FCMClient, uid uint32, title, body string) {
	apnsTok, fcmTok, err := store.GetTokens(uid)
	if err != nil {
		slog.Warn("get tokens", "uid", uid, "err", err)
		return
	}
	if apnsTok != "" && apns != nil {
		if err := apns.Push(apnsTok, title, body); err != nil {
			slog.Warn("apns failed", "uid", uid, "err", err)
		}
	}
	if fcmTok != "" && fcm != nil {
		if err := fcm.Push(fcmTok, title, body); err != nil {
			slog.Warn("fcm failed", "uid", uid, "err", err)
		}
	}
}

func setupTopology(ch *amqp.Channel) error {
	if err := ch.ExchangeDeclare(exchange, "topic", true, false, false, false, nil); err != nil {
		return err
	}
	if _, err := ch.QueueDeclare(tomcatsQueue, true, false, false, false, nil); err != nil {
		return err
	}
	for _, key := range []string{"topup.result"} {
		if err := ch.QueueBind(tomcatsQueue, key, exchange, false, nil); err != nil {
			return err
		}
	}
	return nil
}

func fmtVND(n uint64) string {
	s := fmt.Sprintf("%d", n)
	var b strings.Builder
	for i, c := range s {
		if i > 0 && (len(s)-i)%3 == 0 {
			b.WriteByte('.')
		}
		b.WriteRune(c)
	}
	return b.String()
}
