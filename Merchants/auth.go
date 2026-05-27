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

const jwtTTL = 24 * time.Hour

// jwtIssue signs a HS256 JWT: {sub=uid, aud=slug, iat, exp}.
func jwtIssue(uid uint32, slug, secret string) (string, error) {
	if secret == "" {
		return "", errors.New("jwt secret not configured")
	}
	hdr := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	claimsJSON, _ := json.Marshal(map[string]any{
		"sub": uid,
		"aud": slug,
		"iat": time.Now().Unix(),
		"exp": time.Now().Add(jwtTTL).Unix(),
	})
	payload := base64.RawURLEncoding.EncodeToString(claimsJSON)
	msg := hdr + "." + payload
	sig := jwtSign(msg, secret)
	return msg + "." + sig, nil
}

// jwtVerify checks signature + expiry, returns (uid, slug).
func jwtVerify(token, secret string) (uid uint32, slug string, err error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return 0, "", errors.New("malformed token")
	}
	msg := parts[0] + "." + parts[1]
	if !hmac.Equal([]byte(jwtSign(msg, secret)), []byte(parts[2])) {
		return 0, "", errors.New("invalid signature")
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return 0, "", errors.New("bad payload encoding")
	}
	var claims struct {
		Sub uint32 `json:"sub"`
		Aud string `json:"aud"`
		Exp int64  `json:"exp"`
	}
	if err = json.Unmarshal(raw, &claims); err != nil {
		return 0, "", err
	}
	if time.Now().Unix() > claims.Exp {
		return 0, "", errors.New("token expired")
	}
	return claims.Sub, claims.Aud, nil
}

func jwtSign(msg, secret string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(msg))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}
