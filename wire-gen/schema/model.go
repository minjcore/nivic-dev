package schema

import "strings"

type FieldType int

const (
	TypeU8    FieldType = iota
	TypeU16
	TypeU32
	TypeU64
	TypeBytes
)

func (t FieldType) String() string {
	switch t {
	case TypeU8:
		return "u8"
	case TypeU16:
		return "u16"
	case TypeU32:
		return "u32"
	case TypeU64:
		return "u64"
	case TypeBytes:
		return "bytes"
	}
	return "unknown"
}

func (t FieldType) ByteSize() int {
	switch t {
	case TypeU8:
		return 1
	case TypeU16:
		return 2
	case TypeU32:
		return 4
	case TypeU64:
		return 8
	}
	return -1
}

type Field struct {
	Name     string // snake_case from schema
	Type     FieldType
	Size     int  // fixed byte count; -1 = variable
	Skip     bool // padding — omit from generated struct
	Var      bool // variable-length field (only one allowed)
	Trailing bool // after the MAC/signed region

	Offset int // computed: byte offset from frame start
}

// ByteSize returns fixed byte size of this field, or -1 if variable.
func (f *Field) ByteSize() int {
	if f.Type == TypeBytes {
		return f.Size
	}
	return f.Type.ByteSize()
}

// CamelCase returns field name in UpperCamelCase.
func (f *Field) CamelCase() string {
	parts := strings.Split(f.Name, "_")
	for i, p := range parts {
		if len(p) > 0 {
			parts[i] = strings.ToUpper(p[:1]) + p[1:]
		}
	}
	return strings.Join(parts, "")
}

// LowerCamel returns field name in lowerCamelCase.
func (f *Field) LowerCamel() string {
	cc := f.CamelCase()
	if len(cc) == 0 {
		return cc
	}
	return strings.ToLower(cc[:1]) + cc[1:]
}

// ConstName returns field name in UPPER_SNAKE_CASE.
func (f *Field) ConstName() string {
	return strings.ToUpper(f.Name)
}

// Protocol is the parsed + computed protocol definition.
type Protocol struct {
	Name   string
	Fields []*Field

	// Computed after Parse:
	MinWireLen  int // minimum total bytes (var field empty)
	PrefixLen   int // bytes before var field (PREFIX_BEFORE_EXTRA_LEN)
	TrailingLen int // bytes after var field (sig etc.)
	SignedStart int // offset of first non-skip field (MAC starts here)
}

// FixedFields returns non-skip, non-var fields (have a fixed offset).
func (p *Protocol) FixedFields() []*Field {
	var out []*Field
	for _, f := range p.Fields {
		if !f.Skip && !f.Var {
			out = append(out, f)
		}
	}
	return out
}

// VarField returns the single variable-length field, or nil.
func (p *Protocol) VarField() *Field {
	for _, f := range p.Fields {
		if f.Var {
			return f
		}
	}
	return nil
}

// TrailingFields returns fields after the var region.
func (p *Protocol) TrailingFields() []*Field {
	var out []*Field
	for _, f := range p.Fields {
		if f.Trailing {
			out = append(out, f)
		}
	}
	return out
}
