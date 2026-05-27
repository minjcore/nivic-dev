package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/smtp"
	"os"
	"strconv"
	"time"
)

type mailerConfig struct {
	host     string
	port     int
	user     string
	password string
	from     string
	fromName string
}

type mailer struct {
	cfg mailerConfig
}

func mailerFromEnv() *mailer {
	port := 465
	if v := os.Getenv("SMTP_PORT"); v != "" {
		if p, err := strconv.Atoi(v); err == nil {
			port = p
		}
	}
	password := os.Getenv("SMTP_PASSWORD")
	if password == "" {
		password = os.Getenv("SMTP_PASS")
	}
	fromName := os.Getenv("SMTP_FROM_NAME")
	if fromName == "" {
		fromName = "Nivic Pay"
	}
	cfg := mailerConfig{
		host:     os.Getenv("SMTP_HOST"),
		port:     port,
		user:     os.Getenv("SMTP_USER"),
		password: password,
		from:     os.Getenv("SMTP_FROM"),
		fromName: fromName,
	}
	if cfg.host == "" {
		return nil
	}
	return &mailer{cfg: cfg}
}

func (m *mailer) send(ctx context.Context, to, subject, htmlBody string) error {
	if m == nil || m.cfg.host == "" {
		return nil
	}
	from := m.cfg.from
	if from == "" {
		from = m.cfg.user
	}

	header := fmt.Sprintf(
		"From: %s <%s>\r\nTo: %s\r\nSubject: %s\r\nMIME-Version: 1.0\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n",
		m.cfg.fromName, from, to, subject,
	)
	msg := []byte(header + htmlBody)
	auth := smtp.PlainAuth("", m.cfg.user, m.cfg.password, m.cfg.host)
	addr := fmt.Sprintf("%s:%d", m.cfg.host, m.cfg.port)

	done := make(chan error, 1)
	go func() {
		if m.cfg.port == 465 {
			done <- sendSSL(addr, m.cfg.host, auth, from, []string{to}, msg)
		} else {
			done <- smtp.SendMail(addr, auth, from, []string{to}, msg)
		}
	}()

	select {
	case <-ctx.Done():
		return ctx.Err()
	case err := <-done:
		return err
	}
}

func sendSSL(addr, host string, auth smtp.Auth, from string, to []string, msg []byte) error {
	conn, err := tls.DialWithDialer(&net.Dialer{Timeout: 15 * time.Second}, "tcp", addr, &tls.Config{ServerName: host})
	if err != nil {
		return fmt.Errorf("tls dial: %w", err)
	}
	defer conn.Close()

	c, err := smtp.NewClient(conn, host)
	if err != nil {
		return fmt.Errorf("smtp client: %w", err)
	}
	defer c.Close()

	if err := c.Auth(auth); err != nil {
		return fmt.Errorf("auth: %w", err)
	}
	if err := c.Mail(from); err != nil {
		return fmt.Errorf("MAIL FROM: %w", err)
	}
	for _, r := range to {
		if err := c.Rcpt(r); err != nil {
			return fmt.Errorf("RCPT TO %s: %w", r, err)
		}
	}
	w, err := c.Data()
	if err != nil {
		return fmt.Errorf("DATA: %w", err)
	}
	if _, err := w.Write(msg); err != nil {
		return fmt.Errorf("write body: %w", err)
	}
	return w.Close()
}

// email templates

func emailOrderPaid(merchantName, orderID string, amount uint64, pointsAwarded int64) string {
	discount := ""
	if pointsAwarded > 0 {
		discount = fmt.Sprintf(`<p style="color:#27ae60">+%d điểm thưởng đã được cộng vào tài khoản của bạn.</p>`, pointsAwarded)
	}
	return fmt.Sprintf(`<!DOCTYPE html><html><body style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:24px">
<h2 style="color:#2c3e50">Thanh toán thành công</h2>
<p>Cảm ơn bạn đã thanh toán tại <strong>%s</strong>.</p>
<table style="width:100%%;border-collapse:collapse">
  <tr><td style="padding:8px;color:#888">Mã đơn hàng</td><td style="padding:8px;font-weight:bold">%s</td></tr>
  <tr style="background:#f8f8f8"><td style="padding:8px;color:#888">Số tiền</td><td style="padding:8px;font-weight:bold">%s VND</td></tr>
</table>
%s
<hr style="margin:24px 0;border:none;border-top:1px solid #eee">
<p style="color:#aaa;font-size:12px">Nivic Pay — Hệ thống thanh toán điện tử</p>
</body></html>`, merchantName, orderID, formatVND(int64(amount)), discount)
}

func emailWelcome(merchantName string) string {
	return fmt.Sprintf(`<!DOCTYPE html><html><body style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:24px">
<h2 style="color:#2c3e50">Chào mừng đến với %s!</h2>
<p>Bạn đã tham gia chương trình tích điểm của <strong>%s</strong>.</p>
<p>Mỗi 1.000 VND chi tiêu = 1 điểm thưởng. Tích điểm để nhận ưu đãi hấp dẫn.</p>
<hr style="margin:24px 0;border:none;border-top:1px solid #eee">
<p style="color:#aaa;font-size:12px">Nivic Pay — Hệ thống thanh toán điện tử</p>
</body></html>`, merchantName, merchantName)
}
