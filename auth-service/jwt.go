package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"time"
)

var jwtKey = func() []byte {
	if s := os.Getenv("JWT_SECRET"); s != "" {
		return []byte(s)
	}
	return []byte("saving_jwt_secret_changeme")
}()

type Claims struct {
	UID uint32 `json:"uid"`
	WT  string `json:"wt"`  // base64url wire token (32 B)
	Exp int64  `json:"exp"`
}

func issueJWT(uid uint32, wireToken []byte) (string, error) {
	hdr, _ := json.Marshal(map[string]string{"alg": "HS256", "typ": "JWT"})
	pay, _ := json.Marshal(Claims{
		UID: uid,
		WT:  base64.RawURLEncoding.EncodeToString(wireToken),
		Exp: time.Now().Add(15 * time.Minute).Unix(),
	})
	hp := base64.RawURLEncoding.EncodeToString(hdr) + "." +
		base64.RawURLEncoding.EncodeToString(pay)
	mac := hmac.New(sha256.New, jwtKey)
	mac.Write([]byte(hp))
	return hp + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil)), nil
}

// VerifyClaims is duplicated in Tomcats/jwt.go — keep in sync if secret logic changes.
func verifyClaims(token string) (*Claims, error) {
	parts := strings.SplitN(token, ".", 3)
	if len(parts) != 3 {
		return nil, fmt.Errorf("malformed token")
	}
	mac := hmac.New(sha256.New, jwtKey)
	mac.Write([]byte(parts[0] + "." + parts[1]))
	if !hmac.Equal(
		[]byte(base64.RawURLEncoding.EncodeToString(mac.Sum(nil))),
		[]byte(parts[2]),
	) {
		return nil, fmt.Errorf("invalid signature")
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	var c Claims
	if err := json.Unmarshal(raw, &c); err != nil {
		return nil, fmt.Errorf("parse claims: %w", err)
	}
	if time.Now().Unix() > c.Exp {
		return nil, fmt.Errorf("expired")
	}
	return &c, nil
}
