package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

// issueJWT signs a HS256 JWT. wireToken (32B) is embedded as base64url "wt" claim
// so downstream services (Tomcats, saving-gateway) can make Wire calls on behalf of the user.
func issueJWT(uid uint32, wireToken []byte, secret string, ttl time.Duration) (string, int64, error) {
	if secret == "" {
		return "", 0, errors.New("jwt secret not configured")
	}
	now := time.Now()
	exp := now.Add(ttl)
	hdr := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	claims := map[string]any{
		"iss": "iam.nivic.dev",
		"sub": uid,
		"aud": "saving",
		"iat": now.Unix(),
		"exp": exp.Unix(),
	}
	if len(wireToken) == 32 {
		claims["wt"] = base64.RawURLEncoding.EncodeToString(wireToken)
	}
	claimsJSON, _ := json.Marshal(claims)
	payload := base64.RawURLEncoding.EncodeToString(claimsJSON)
	msg := hdr + "." + payload
	sig := jwtSign(msg, secret)
	return msg + "." + sig, exp.Unix(), nil
}

func verifyJWT(token, secret string) (uid uint32, err error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return 0, errors.New("malformed token")
	}
	msg := parts[0] + "." + parts[1]
	if !hmac.Equal([]byte(jwtSign(msg, secret)), []byte(parts[2])) {
		return 0, errors.New("invalid signature")
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return 0, errors.New("bad payload encoding")
	}
	var claims struct {
		Sub uint32 `json:"sub"`
		Exp int64  `json:"exp"`
	}
	if err = json.Unmarshal(raw, &claims); err != nil {
		return 0, err
	}
	if time.Now().Unix() > claims.Exp {
		return 0, errors.New("token expired")
	}
	return claims.Sub, nil
}

func jwtSign(msg, secret string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(msg))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}
