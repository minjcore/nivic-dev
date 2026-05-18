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
	url   = "amqp://admin:admin@localhost:5672/"
	queue = "nivic.test"
)

func dial(label string) *amqp.Connection {
	for attempt := 1; ; attempt++ {
		conn, err := amqp.Dial(url)
		if err == nil {
			log.Printf("[%s] connected → %s", label, conn.RemoteAddr())
			return conn
		}
		log.Printf("[%s] attempt %d failed: %v — retry 1s", label, attempt, err)
		time.Sleep(time.Second)
	}
}

func openChannel(conn *amqp.Connection) (*amqp.Channel, error) {
	ch, err := conn.Channel()
	if err != nil {
		return nil, err
	}
	_, err = ch.QueueDeclare(queue, true, false, false, false, nil)
	return ch, err
}

// runPublisher publishes one message every 300ms; auto-reconnects on failure.
func runPublisher(ctx context.Context, id int, seq *atomic.Int64, sent, errs *atomic.Int64) {
	label := fmt.Sprintf("pub-%d", id)

	connect := func() (*amqp.Connection, *amqp.Channel) {
		for {
			conn := dial(label)
			ch, err := openChannel(conn)
			if err == nil {
				return conn, ch
			}
			log.Printf("[%s] channel err: %v", label, err)
			conn.Close()
			time.Sleep(time.Second)
		}
	}

	conn, ch := connect()
	closed := conn.NotifyClose(make(chan *amqp.Error, 1))
	ticker := time.NewTicker(300 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			conn.Close()
			return

		case err := <-closed:
			log.Printf("[%s] *** connection lost: %v — reconnecting…", label, err)
			conn, ch = connect()
			closed = conn.NotifyClose(make(chan *amqp.Error, 1))

		case <-ticker.C:
			n := seq.Add(1)
			body := fmt.Sprintf("msg-%04d  pub=%d  t=%d", n, id, time.Now().UnixMilli())
			err := ch.PublishWithContext(ctx, "", queue, false, false,
				amqp.Publishing{
					ContentType:  "text/plain",
					DeliveryMode: amqp.Persistent,
					Body:         []byte(body),
				})
			if err != nil {
				log.Printf("[%s] *** publish FAIL #%04d: %v", label, n, err)
				errs.Add(1)
			} else {
				sent.Add(1)
				log.Printf("[%s] sent #%04d", label, n)
			}
		}
	}
}

// runConsumer consumes from the queue; auto-reconnects on failure.
func runConsumer(ctx context.Context, received *atomic.Int64) {
	label := "consumer"
	for {
		if ctx.Err() != nil {
			return
		}
		conn := dial(label)
		ch, err := openChannel(conn)
		if err != nil {
			log.Printf("[%s] channel: %v", label, err)
			conn.Close()
			time.Sleep(time.Second)
			continue
		}
		msgs, err := ch.Consume(queue, "nivic-consumer", true, false, false, false, nil)
		if err != nil {
			log.Printf("[%s] consume: %v", label, err)
			conn.Close()
			time.Sleep(time.Second)
			continue
		}

		closed := conn.NotifyClose(make(chan *amqp.Error, 1))
		log.Printf("[%s] ready on %s", label, conn.RemoteAddr())

	drain:
		for {
			select {
			case <-ctx.Done():
				conn.Close()
				return
			case err := <-closed:
				log.Printf("[%s] *** connection lost: %v — reconnecting…", label, err)
				conn.Close()
				break drain
			case msg, ok := <-msgs:
				if !ok {
					break drain
				}
				n := received.Add(1)
				log.Printf("[%s] recv #%04d: %s", label, n, msg.Body)
			}
		}
	}
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	var seq, sent, errs, received atomic.Int64

	// 3 publishers → roundrobin guarantees 1 connection per node
	for i := 1; i <= 3; i++ {
		go runPublisher(ctx, i, &seq, &sent, &errs)
	}
	go runConsumer(ctx, &received)

	log.Println("3 publishers running (one per node via roundrobin)")
	log.Println("kill any rabbitmq node to trigger failover — Ctrl-C to stop")

	<-ctx.Done()
	time.Sleep(400 * time.Millisecond)
	log.Printf("─── result: sent=%d  received=%d  pub_errors=%d ───",
		sent.Load(), received.Load(), errs.Load())
}
