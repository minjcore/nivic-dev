package schema

import (
	"bufio"
	"fmt"
	"io"
	"strconv"
	"strings"
)

// Parse reads a .wproto schema and returns a Protocol with computed offsets.
//
// Syntax:
//
//	# comment
//	protocol Name {
//	    field_name   u8 | u16 | u32 | u64 | bytes(N) | bytes(*)   [skip] [var] [trailing]
//	    ...
//	}
func Parse(r io.Reader) (*Protocol, error) {
	scanner := bufio.NewScanner(r)
	var proto *Protocol
	lineNo := 0

	for scanner.Scan() {
		lineNo++
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}

		if proto == nil {
			// Expect: protocol Name {
			if !strings.HasPrefix(line, "protocol ") {
				return nil, fmt.Errorf("line %d: expected 'protocol Name {', got: %q", lineNo, line)
			}
			rest := strings.TrimPrefix(line, "protocol ")
			rest = strings.TrimSpace(rest)
			rest = strings.TrimSuffix(rest, "{")
			name := strings.TrimSpace(rest)
			if name == "" {
				return nil, fmt.Errorf("line %d: missing protocol name", lineNo)
			}
			proto = &Protocol{Name: name}
			continue
		}

		if line == "}" {
			break
		}

		f, err := parseField(line, lineNo)
		if err != nil {
			return nil, err
		}
		proto.Fields = append(proto.Fields, f)
	}

	if err := scanner.Err(); err != nil {
		return nil, err
	}
	if proto == nil {
		return nil, fmt.Errorf("no protocol definition found")
	}

	if err := computeOffsets(proto); err != nil {
		return nil, err
	}
	return proto, nil
}

func parseField(line string, lineNo int) (*Field, error) {
	// Split into tokens: name, type, optional [tags...]
	tokens := strings.Fields(line)
	if len(tokens) < 2 {
		return nil, fmt.Errorf("line %d: too few tokens in field: %q", lineNo, line)
	}

	f := &Field{Name: tokens[0]}

	// Parse type token: u8 | u16 | u32 | u64 | bytes(N) | bytes(*)
	typeTok := tokens[1]
	if err := parseType(f, typeTok, lineNo); err != nil {
		return nil, err
	}

	// Parse optional tags: [skip] [var] [trailing]
	for _, tok := range tokens[2:] {
		tag := strings.Trim(tok, "[]")
		switch strings.ToLower(tag) {
		case "skip":
			f.Skip = true
		case "var":
			f.Var = true
			f.Size = -1
		case "trailing":
			f.Trailing = true
		default:
			return nil, fmt.Errorf("line %d: unknown tag %q", lineNo, tok)
		}
	}

	return f, nil
}

func parseType(f *Field, tok string, lineNo int) error {
	switch tok {
	case "u8":
		f.Type = TypeU8
	case "u16":
		f.Type = TypeU16
	case "u32":
		f.Type = TypeU32
	case "u64":
		f.Type = TypeU64
	default:
		// bytes(N) or bytes(*)
		if !strings.HasPrefix(tok, "bytes(") || !strings.HasSuffix(tok, ")") {
			return fmt.Errorf("line %d: unknown type %q", lineNo, tok)
		}
		inner := tok[len("bytes(") : len(tok)-1]
		f.Type = TypeBytes
		if inner == "*" {
			f.Size = -1
			f.Var = true
		} else {
			n, err := strconv.Atoi(inner)
			if err != nil || n <= 0 {
				return fmt.Errorf("line %d: invalid bytes size %q", lineNo, inner)
			}
			f.Size = n
		}
	}
	return nil
}

func computeOffsets(p *Protocol) error {
	varCount := 0
	for _, f := range p.Fields {
		if f.Var {
			varCount++
		}
	}
	if varCount > 1 {
		return fmt.Errorf("protocol %q: only one var field allowed, found %d", p.Name, varCount)
	}

	offset := 0
	pastVar := false
	signedStart := -1
	prefixLen := 0
	trailingLen := 0

	for _, f := range p.Fields {
		if f.Var {
			f.Offset = offset
			prefixLen = offset
			pastVar = true
			continue
		}
		if pastVar {
			f.Trailing = true
		}
		if !f.Skip && signedStart < 0 {
			signedStart = offset
		}
		f.Offset = offset
		sz := f.ByteSize()
		if sz < 0 {
			return fmt.Errorf("field %q: variable size in non-var field", f.Name)
		}
		offset += sz
		if pastVar || f.Trailing {
			trailingLen += sz
		}
	}

	if varCount == 0 {
		prefixLen = offset
	}

	if signedStart < 0 {
		signedStart = 0
	}

	p.PrefixLen = prefixLen
	p.TrailingLen = trailingLen
	p.MinWireLen = prefixLen + trailingLen
	p.SignedStart = signedStart
	return nil
}
