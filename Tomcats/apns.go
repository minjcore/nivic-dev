package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"sync"
	"time"
)

type APNsClient struct {
	keyID      string
	teamID     string
	bundleID   string
	privateKey *ecdsa.PrivateKey
	baseURL    string

	mu     sync.Mutex
	jwt    string
	jwtExp time.Time
	hc     *http.Client
}

func NewAPNsClientFromEnv() (*APNsClient, error) {
	keyID    := os.Getenv("APNS_KEY_ID")
	teamID   := os.Getenv("APNS_TEAM_ID")
	bundleID := os.Getenv("APNS_BUNDLE_ID")
	keyPath  := os.Getenv("APNS_KEY_PATH")
	env      := os.Getenv("APNS_ENV")

	if keyID == "" || teamID == "" || bundleID == "" || keyPath == "" {
		return nil, fmt.Errorf("APNS_KEY_ID / APNS_TEAM_ID / APNS_BUNDLE_ID / APNS_KEY_PATH not set")
	}

	data, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, fmt.Errorf("read key: %w", err)
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, fmt.Errorf("no PEM block in %s", keyPath)
	}
	raw, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse key: %w", err)
	}
	ecKey, ok := raw.(*ecdsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("key is not EC P-256")
	}

	baseURL := "https://api.sandbox.push.apple.com"
	if env == "production" {
		baseURL = "https://api.push.apple.com"
	}

	return &APNsClient{
		keyID:      keyID,
		teamID:     teamID,
		bundleID:   bundleID,
		privateKey: ecKey,
		baseURL:    baseURL,
		hc:         &http.Client{Timeout: 10 * time.Second},
	}, nil
}

func (c *APNsClient) jwtToken() (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if time.Now().Before(c.jwtExp) {
		return c.jwt, nil
	}
	hdr := b64url(mustJSON(map[string]string{"alg": "ES256", "kid": c.keyID}))
	pld := b64url(mustJSON(map[string]any{"iss": c.teamID, "iat": time.Now().Unix()}))
	msg := hdr + "." + pld
	hash := sha256.Sum256([]byte(msg))
	r, s, err := ecdsa.Sign(rand.Reader, c.privateKey, hash[:])
	if err != nil {
		return "", err
	}
	sig := b64url(append(r.FillBytes(make([]byte, 32)), s.FillBytes(make([]byte, 32))...))
	c.jwt = msg + "." + sig
	c.jwtExp = time.Now().Add(55 * time.Minute)
	return c.jwt, nil
}

func (c *APNsClient) Push(deviceToken, title, body string) error {
	tok, err := c.jwtToken()
	if err != nil {
		return fmt.Errorf("apns jwt: %w", err)
	}
	payload, _ := json.Marshal(map[string]any{
		"aps": map[string]any{
			"alert": map[string]string{"title": title, "body": body},
			"sound": "default",
		},
	})
	req, _ := http.NewRequest(http.MethodPost,
		c.baseURL+"/3/device/"+deviceToken,
		bytes.NewReader(payload))
	req.Header.Set("authorization", "bearer "+tok)
	req.Header.Set("apns-topic", c.bundleID)
	req.Header.Set("apns-push-type", "alert")
	req.Header.Set("apns-priority", "10")
	req.Header.Set("content-type", "application/json")

	resp, err := c.hc.Do(req)
	if err != nil {
		return fmt.Errorf("apns send: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		var e struct{ Reason string `json:"reason"` }
		json.NewDecoder(resp.Body).Decode(&e)
		return fmt.Errorf("apns %d: %s", resp.StatusCode, e.Reason)
	}
	slog.Info("apns push sent", "token_prefix", deviceToken[:8]+"...", "title", title)
	return nil
}

func b64url(data []byte) string { return base64.RawURLEncoding.EncodeToString(data) }
func mustJSON(v any) []byte     { b, _ := json.Marshal(v); return b }
