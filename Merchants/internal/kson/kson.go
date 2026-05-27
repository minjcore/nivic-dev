// Package kson — embedded copy for Merchants binary.
// dotted-path nesting, inline objects, and arrays in a single format.
//
// Syntax overview:
//
//	# comment
//	HOST     = localhost          # string (unquoted)
//	PORT     = 8080               # int64
//	DEBUG    = false              # bool
//	RATIO    = 3.14               # float64
//
//	db.host     = localhost       # dotted path → nested map
//	db.pool.max = 8
//
//	smtp = { host: smtpdm.aliyun.com, port: 465, ssl: true }  # inline object
//	tags = [auth, wallet, orders]                               # array
//	dsn  = "postgres://u:p@localhost/db?sslmode=disable"        # quoted string
//
// Env-var interpolation: ${VAR} or $VAR in values.
// Env override: if KSON_<KEY> (dots→underscores, uppercased) is set, it wins.
package kson

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"unicode"
)

// Parse parses kson-formatted bytes into map[string]any.
// Dotted keys create nested maps: "db.host = x" → {"db": {"host": "x"}}.
func Parse(src []byte) (map[string]any, error) {
	p := &parser{src: src, line: 1}
	return p.document()
}

// ParseFile reads path and calls Parse.
func ParseFile(path string) (map[string]any, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return Parse(b)
}

// Get retrieves a value from a parsed map by dotted path.
// Returns nil if the path does not exist.
func Get(m map[string]any, path string) any {
	parts := strings.SplitN(path, ".", 2)
	v, ok := m[parts[0]]
	if !ok {
		return nil
	}
	if len(parts) == 1 {
		return v
	}
	sub, ok := v.(map[string]any)
	if !ok {
		return nil
	}
	return Get(sub, parts[1])
}

// GetString returns a string value at path, or def if missing/wrong type.
func GetString(m map[string]any, path, def string) string {
	v := Get(m, path)
	if v == nil {
		return def
	}
	switch t := v.(type) {
	case string:
		return t
	default:
		return fmt.Sprintf("%v", v)
	}
}

// GetInt returns an int64 value at path, or def if missing/wrong type.
func GetInt(m map[string]any, path string, def int64) int64 {
	v := Get(m, path)
	if v == nil {
		return def
	}
	switch t := v.(type) {
	case int64:
		return t
	case float64:
		return int64(t)
	case string:
		if i, err := strconv.ParseInt(t, 10, 64); err == nil {
			return i
		}
	}
	return def
}

// GetBool returns a bool value at path, or def if missing/wrong type.
func GetBool(m map[string]any, path string, def bool) bool {
	v := Get(m, path)
	if v == nil {
		return def
	}
	switch t := v.(type) {
	case bool:
		return t
	case string:
		switch strings.ToLower(t) {
		case "true", "yes", "on", "1":
			return true
		case "false", "no", "off", "0":
			return false
		}
	case int64:
		return t != 0
	}
	return def
}

// GetMap returns a nested map at path, or nil if missing/wrong type.
func GetMap(m map[string]any, path string) map[string]any {
	v := Get(m, path)
	if v == nil {
		return nil
	}
	sub, _ := v.(map[string]any)
	return sub
}

// ─── parser ──────────────────────────────────────────────────────────────────

type parser struct {
	src  []byte
	pos  int
	line int
}

func (p *parser) document() (map[string]any, error) {
	out := make(map[string]any)
	for {
		p.skipBlanks()
		if p.pos >= len(p.src) {
			break
		}
		c := p.src[p.pos]
		if c == '#' {
			p.skipToEOL()
			continue
		}
		if c == '\n' || c == '\r' {
			p.advance()
			continue
		}

		key, err := p.key()
		if err != nil {
			return nil, err
		}

		p.skipInline()

		if !p.expect('=') {
			return nil, p.errf("expected '=' after key %q", key)
		}
		p.skipInline()

		val, err := p.value()
		if err != nil {
			return nil, err
		}

		// env override: KSON_DB_HOST wins over db.host
		envKey := "KSON_" + strings.ToUpper(strings.ReplaceAll(key, ".", "_"))
		if override := os.Getenv(envKey); override != "" {
			val = inferType(override)
		}

		if err := setNested(out, key, val); err != nil {
			return nil, p.errf("%v", err)
		}

		p.skipInline()
		if p.pos < len(p.src) && p.src[p.pos] == '#' {
			p.skipToEOL()
		}
		if p.pos < len(p.src) && (p.src[p.pos] == '\n' || p.src[p.pos] == '\r') {
			p.advance()
		}
	}
	return out, nil
}

// key reads letters, digits, '.', '-', '_', '/'.
func (p *parser) key() (string, error) {
	start := p.pos
	for p.pos < len(p.src) {
		c := p.src[p.pos]
		if c == '=' || c == ' ' || c == '\t' || c == '\n' || c == '\r' {
			break
		}
		if !isKeyByte(c) {
			return "", p.errf("invalid character %q in key", rune(c))
		}
		p.pos++
	}
	if p.pos == start {
		return "", p.errf("empty key")
	}
	return string(p.src[start:p.pos]), nil
}

// value dispatches on the first non-blank character.
func (p *parser) value() (any, error) {
	if p.pos >= len(p.src) || p.src[p.pos] == '\n' {
		return "", nil
	}
	switch p.src[p.pos] {
	case '{':
		return p.object()
	case '[':
		return p.array()
	case '"', '\'':
		return p.quoted(p.src[p.pos])
	default:
		return p.scalar()
	}
}

// object parses { key: value, ... }
func (p *parser) object() (map[string]any, error) {
	p.pos++ // '{'
	obj := make(map[string]any)
	for {
		p.skipBlanksAndNewlines()
		if p.pos >= len(p.src) {
			return nil, p.errf("unterminated object")
		}
		if p.src[p.pos] == '}' {
			p.pos++
			return obj, nil
		}
		if p.src[p.pos] == ',' {
			p.pos++
			continue
		}
		// key (quoted or bare)
		var k string
		var err error
		if p.src[p.pos] == '"' || p.src[p.pos] == '\'' {
			k, err = p.quoted(p.src[p.pos])
		} else {
			k, err = p.bareObjectKey()
		}
		if err != nil {
			return nil, err
		}
		p.skipInline()
		if !p.expect(':') {
			return nil, p.errf("expected ':' after object key %q", k)
		}
		p.skipInline()
		v, err := p.value()
		if err != nil {
			return nil, err
		}
		obj[k] = v
		p.skipInline()
	}
}

// array parses [val, val, ...]
func (p *parser) array() ([]any, error) {
	p.pos++ // '['
	var arr []any
	for {
		p.skipBlanksAndNewlines()
		if p.pos >= len(p.src) {
			return nil, p.errf("unterminated array")
		}
		if p.src[p.pos] == ']' {
			p.pos++
			return arr, nil
		}
		if p.src[p.pos] == ',' {
			p.pos++
			continue
		}
		v, err := p.value()
		if err != nil {
			return nil, err
		}
		arr = append(arr, v)
		p.skipInline()
	}
}

// quoted reads a "..." or '...' string with backslash escapes.
func (p *parser) quoted(delim byte) (string, error) {
	p.pos++ // opening quote
	var b strings.Builder
	for p.pos < len(p.src) {
		c := p.src[p.pos]
		if c == delim {
			p.pos++
			s := expandEnv(b.String())
			return s, nil
		}
		if c == '\\' && p.pos+1 < len(p.src) {
			p.pos++
			switch p.src[p.pos] {
			case 'n':
				b.WriteByte('\n')
			case 't':
				b.WriteByte('\t')
			case 'r':
				b.WriteByte('\r')
			default:
				b.WriteByte(p.src[p.pos])
			}
		} else {
			if c == '\n' {
				p.line++
			}
			b.WriteByte(c)
		}
		p.pos++
	}
	return "", p.errf("unterminated string")
}

// scalar reads an unquoted value until EOL, '#', ',', '}', or ']'.
// Inside ${...} env-var braces, stop chars are suppressed so the full
// ${VAR} token is captured before expandEnv runs.
func (p *parser) scalar() (any, error) {
	start := p.pos
	inBrace := 0
	for p.pos < len(p.src) {
		c := p.src[p.pos]
		if c == '$' && p.pos+1 < len(p.src) && p.src[p.pos+1] == '{' {
			inBrace++
			p.pos += 2
			continue
		}
		if c == '}' {
			if inBrace > 0 {
				inBrace--
				p.pos++
				continue
			}
			break
		}
		if inBrace == 0 && (c == '\n' || c == '\r' || c == '#' || c == ',' || c == ']') {
			break
		}
		if c == '\n' || c == '\r' {
			break
		}
		p.pos++
	}
	raw := strings.TrimRightFunc(string(p.src[start:p.pos]), unicode.IsSpace)
	return inferType(expandEnv(raw)), nil
}

// bareObjectKey reads an unquoted object key (stops at ':', ' ', '\t').
func (p *parser) bareObjectKey() (string, error) {
	start := p.pos
	for p.pos < len(p.src) {
		c := p.src[p.pos]
		if c == ':' || c == ' ' || c == '\t' {
			break
		}
		p.pos++
	}
	if p.pos == start {
		return "", p.errf("empty object key")
	}
	return string(p.src[start:p.pos]), nil
}

// ─── helpers ──────────────────────────────────────────────────────────────────

// inferType converts a raw string to bool, int64, float64, or string.
func inferType(s string) any {
	switch strings.ToLower(s) {
	case "true", "yes", "on":
		return true
	case "false", "no", "off":
		return false
	case "null", "~", "nil":
		return nil
	}
	if i, err := strconv.ParseInt(s, 10, 64); err == nil {
		return i
	}
	if f, err := strconv.ParseFloat(s, 64); err == nil {
		return f
	}
	return s
}

// expandEnv replaces ${VAR} and $VAR with os.Getenv values.
func expandEnv(s string) string {
	return os.Expand(s, os.Getenv)
}

// setNested sets a dotted path into a nested map[string]any.
func setNested(m map[string]any, key string, val any) error {
	dot := strings.IndexByte(key, '.')
	if dot < 0 {
		m[key] = val
		return nil
	}
	head, tail := key[:dot], key[dot+1:]
	existing, ok := m[head]
	if !ok {
		sub := make(map[string]any)
		m[head] = sub
		return setNested(sub, tail, val)
	}
	sub, ok := existing.(map[string]any)
	if !ok {
		return fmt.Errorf("key conflict: %q is already a scalar, cannot nest %q under it", head, tail)
	}
	return setNested(sub, tail, val)
}

func (p *parser) skipBlanks() {
	for p.pos < len(p.src) {
		c := p.src[p.pos]
		if c != ' ' && c != '\t' {
			return
		}
		p.pos++
	}
}

func (p *parser) skipInline() {
	for p.pos < len(p.src) {
		c := p.src[p.pos]
		if c != ' ' && c != '\t' {
			return
		}
		p.pos++
	}
}

func (p *parser) skipBlanksAndNewlines() {
	for p.pos < len(p.src) {
		c := p.src[p.pos]
		if c == '\n' || c == '\r' {
			p.line++
		}
		if c != ' ' && c != '\t' && c != '\n' && c != '\r' {
			return
		}
		p.pos++
	}
}

func (p *parser) skipToEOL() {
	for p.pos < len(p.src) && p.src[p.pos] != '\n' {
		p.pos++
	}
}

func (p *parser) advance() {
	if p.pos < len(p.src) {
		if p.src[p.pos] == '\n' {
			p.line++
		}
		p.pos++
	}
}

func (p *parser) expect(c byte) bool {
	if p.pos < len(p.src) && p.src[p.pos] == c {
		p.pos++
		return true
	}
	return false
}

func (p *parser) errf(format string, args ...any) error {
	return fmt.Errorf("kson:%d: %s", p.line, fmt.Sprintf(format, args...))
}

func isKeyByte(c byte) bool {
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
		(c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.' || c == '/'
}
