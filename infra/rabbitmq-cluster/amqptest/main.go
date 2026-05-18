package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"sync/atomic"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

const (
	url      = "amqp://admin:admin@localhost:5672/"
	queue    = "nivic.test"
	exchange = ""
	nPublish = 20
)

func main() {
	// ── Publisher ──────────────────────────────────────────────────────────
	pubConn, err := amqp.Dial(url)
	fatal(err, "dial publisher")
	defer pubConn.Close()

	pubCh, err := pubConn.Channel()
	fatal(err, "open publisher channel")
	defer pubCh.Close()

	_, err = pubCh.QueueDeclare(queue, true, false, false, false, nil)
	fatal(err, "declare queue")

	log.Printf("publisher connected via %s", pubConn.RemoteAddr())

	// ── Consumer ───────────────────────────────────────────────────────────
	conConn, err := amqp.Dial(url)
	fatal(err, "dial consumer")
	defer conConn.Close()

	conCh, err := conConn.Channel()
	fatal(err, "open consumer channel")
	defer conCh.Close()

	log.Printf("consumer  connected via %s", conConn.RemoteAddr())

	msgs, err := conCh.Consume(queue, "nivic-consumer", true, false, false, false, nil)
	fatal(err, "consume")

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	var received atomic.Int64

	go func() {
		for msg := range msgs {
			n := received.Add(1)
			log.Printf("  [%02d] recv: %s", n, msg.Body)
			if received.Load() >= nPublish {
				stop()
			}
		}
	}()

	// ── Publish nPublish messages ──────────────────────────────────────────
	for i := 1; i <= nPublish; i++ {
		body := fmt.Sprintf("msg-%02d  t=%d", i, time.Now().UnixMilli())
		err = pubCh.PublishWithContext(ctx, exchange, queue, false, false,
			amqp.Publishing{
				ContentType:  "text/plain",
				DeliveryMode: amqp.Persistent,
				Body:         []byte(body),
			})
		if err != nil {
			log.Printf("publish #%d error: %v", i, err)
			break
		}
		log.Printf("  [%02d] sent: %s", i, body)
		time.Sleep(100 * time.Millisecond)
	}

	log.Println("waiting for all messages...")
	<-ctx.Done()
	log.Printf("done — sent %d, received %d", nPublish, received.Load())
}

func fatal(err error, msg string) {
	if err != nil {
		log.Fatalf("%s: %v", msg, err)
	}
}
