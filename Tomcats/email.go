package main

import (
	"crypto/rand"
	"crypto/tls"
	"fmt"
	"math/big"
	"net/smtp"
	"strings"
)

type Mailer struct {
	host string
	port string
	user string
	pass string
	from string
}

func NewMailer(host, port, user, pass, from string) *Mailer {
	return &Mailer{host: host, port: port, user: user, pass: pass, from: from}
}

func (m *Mailer) Send(to, subject, body string) error {
	msg := strings.Join([]string{
		"From: " + m.from,
		"To: " + to,
		"Subject: " + subject,
		"MIME-Version: 1.0",
		"Content-Type: text/plain; charset=UTF-8",
		"",
		body,
	}, "\r\n")

	addr := m.host + ":" + m.port
	auth := smtp.PlainAuth("", m.user, m.pass, m.host)

	if m.port == "465" {
		// SMTPS: implicit TLS + AUTH LOGIN (Alibaba Direct Mail)
		tlsCfg := &tls.Config{ServerName: m.host}
		conn, err := tls.Dial("tcp", addr, tlsCfg)
		if err != nil {
			return err
		}
		c, err := smtp.NewClient(conn, m.host)
		if err != nil {
			return err
		}
		defer c.Close()
		if err = c.Auth(loginAuth(m.user, m.pass)); err != nil {
			return err
		}
		if err = c.Mail(m.from); err != nil {
			return err
		}
		if err = c.Rcpt(to); err != nil {
			return err
		}
		wc, err := c.Data()
		if err != nil {
			return err
		}
		if _, err = fmt.Fprint(wc, msg); err != nil {
			return err
		}
		return wc.Close()
	}

	// STARTTLS (port 587)
	return smtp.SendMail(addr, auth, m.from, []string{to}, []byte(msg))
}

func (m *Mailer) SendOTP(to, code string) error {
	return m.Send(to, "Mã xác thực Wire – "+code,
		fmt.Sprintf("Mã OTP của bạn là: %s\n\nMã có hiệu lực trong 10 phút.\nKhông chia sẻ mã này với ai.", code))
}

// loginAuth implements AUTH LOGIN for servers that don't support AUTH PLAIN.
type loginAuthData struct{ user, pass string }

func loginAuth(user, pass string) smtp.Auth { return &loginAuthData{user, pass} }

func (a *loginAuthData) Start(_ *smtp.ServerInfo) (string, []byte, error) {
	return "LOGIN", nil, nil
}
func (a *loginAuthData) Next(fromServer []byte, more bool) ([]byte, error) {
	if !more {
		return nil, nil
	}
	prompt := strings.ToUpper(string(fromServer))
	if strings.Contains(prompt, "USERNAME") || strings.Contains(prompt, "USER") {
		return []byte(a.user), nil
	}
	if strings.Contains(prompt, "PASSWORD") || strings.Contains(prompt, "PASS") {
		return []byte(a.pass), nil
	}
	return nil, fmt.Errorf("unexpected prompt: %s", fromServer)
}

func generateOTP() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n), nil
}
