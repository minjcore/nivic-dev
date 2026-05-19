package main

import (
	"crypto/rand"
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
	auth := smtp.PlainAuth("", m.user, m.pass, m.host)
	msg := strings.Join([]string{
		"From: " + m.from,
		"To: " + to,
		"Subject: " + subject,
		"MIME-Version: 1.0",
		"Content-Type: text/plain; charset=UTF-8",
		"",
		body,
	}, "\r\n")
	return smtp.SendMail(m.host+":"+m.port, auth, m.from, []string{to}, []byte(msg))
}

func (m *Mailer) SendOTP(to, code string) error {
	return m.Send(to, "Mã xác thực Wire – "+code,
		fmt.Sprintf("Mã OTP của bạn là: %s\n\nMã có hiệu lực trong 10 phút.\nKhông chia sẻ mã này với ai.", code))
}

func generateOTP() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n), nil
}
