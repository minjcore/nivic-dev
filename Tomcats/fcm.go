package main

import (
	"bytes"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"sync"
	"time"
)

type FCMClient struct {
	projectID   string
	clientEmail string
	privateKey  *rsa.PrivateKey

	mu       sync.Mutex
	accessTok string
	tokExp   time.Time
	hc       *http.Client
}

func NewFCMClientFromEnv() (*FCMClient, error) {
	keyPath := os.Getenv("FCM_KEY_PATH")
	if keyPath == "" {
		return nil, fmt.Errorf("FCM_KEY_PATH not set")
	}

	data, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, fmt.Errorf("read service account: %w", err)
	}

	var sa struct {
		ProjectID   string `json:"project_id"`
		ClientEmail string `json:"client_email"`
		PrivateKey  string `json:"private_key"`
	}
	if err := json.Unmarshal(data, &sa); err != nil {
		return nil, fmt.Errorf("parse service account: %w", err)
	}
	if sa.ProjectID == "" || sa.ClientEmail == "" || sa.PrivateKey == "" {
		return nil, fmt.Errorf("service account missing required fields")
	}

	block, _ := pem.Decode([]byte(sa.PrivateKey))
	if block == nil {
		return nil, fmt.Errorf("no PEM block in private_key")
	}
	raw, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse private key: %w", err)
	}
	rsaKey, ok := raw.(*rsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("private key is not RSA")
	}

	return &FCMClient{
		projectID:   sa.ProjectID,
		clientEmail: sa.ClientEmail,
		privateKey:  rsaKey,
		hc:          &http.Client{Timeout: 10 * time.Second},
	}, nil
}

// accessToken returns a cached OAuth2 access token, refreshing when expired.
func (c *FCMClient) accessToken() (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if time.Now().Before(c.tokExp) {
		return c.accessTok, nil
	}

	now := time.Now().Unix()
	hdr := fcmB64(mustMarshal(map[string]string{"alg": "RS256", "typ": "JWT"}))
	pld := fcmB64(mustMarshal(map[string]any{
		"iss":   c.clientEmail,
		"sub":   c.clientEmail,
		"aud":   "https://oauth2.googleapis.com/token",
		"iat":   now,
		"exp":   now + 3600,
		"scope": "https://www.googleapis.com/auth/firebase.messaging",
	}))
	msg := hdr + "." + pld
	hash := sha256.Sum256([]byte(msg))
	sig, err := rsa.SignPKCS1v15(rand.Reader, c.privateKey, crypto.SHA256, hash[:])
	if err != nil {
		return "", fmt.Errorf("sign jwt: %w", err)
	}
	jwt := msg + "." + fcmB64(sig)

	resp, err := c.hc.PostForm("https://oauth2.googleapis.com/token", url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {jwt},
	})
	if err != nil {
		return "", fmt.Errorf("token exchange: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)

	var tok struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(body, &tok); err != nil || tok.AccessToken == "" {
		return "", fmt.Errorf("token response: %s", body)
	}

	c.accessTok = tok.AccessToken
	c.tokExp = time.Now().Add(time.Duration(tok.ExpiresIn-60) * time.Second)
	return c.accessTok, nil
}

func (c *FCMClient) Push(deviceToken, title, body string) error {
	tok, err := c.accessToken()
	if err != nil {
		return fmt.Errorf("fcm auth: %w", err)
	}

	payload, _ := json.Marshal(map[string]any{
		"message": map[string]any{
			"token": deviceToken,
			"notification": map[string]string{
				"title": title,
				"body":  body,
			},
		},
	})

	req, _ := http.NewRequest(http.MethodPost,
		fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", c.projectID),
		bytes.NewReader(payload))
	req.Header.Set("authorization", "Bearer "+tok)
	req.Header.Set("content-type", "application/json")

	resp, err := c.hc.Do(req)
	if err != nil {
		return fmt.Errorf("fcm send: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		var e struct {
			Error struct{ Message string `json:"message"` } `json:"error"`
		}
		json.NewDecoder(resp.Body).Decode(&e)
		return fmt.Errorf("fcm %d: %s", resp.StatusCode, e.Error.Message)
	}
	slog.Info("fcm push sent", "token_prefix", deviceToken[:8]+"...", "title", title)
	return nil
}

func fcmB64(data []byte) string { return base64.RawURLEncoding.EncodeToString(data) }
func mustMarshal(v any) []byte  { b, _ := json.Marshal(v); return b }
