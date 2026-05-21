package schema

import (
	"strings"
	"testing"
)

func parse(t *testing.T, src string) *Protocol {
	t.Helper()
	p, err := Parse(strings.NewReader(src))
	if err != nil {
		t.Fatalf("unexpected parse error: %v", err)
	}
	return p
}

func parseErr(t *testing.T, src string) error {
	t.Helper()
	_, err := Parse(strings.NewReader(src))
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	return err
}

// ── basic field types ────────────────────────────────────────────────────────

func TestFixedTypes(t *testing.T) {
	p := parse(t, `
protocol Wire {
    a u8
    b u16
    c u32
    d u64
}`)
	want := []struct {
		name string
		typ  FieldType
		off  int
	}{
		{"a", TypeU8, 0},
		{"b", TypeU16, 1},
		{"c", TypeU32, 3},
		{"d", TypeU64, 7},
	}
	if len(p.Fields) != len(want) {
		t.Fatalf("fields len: got %d want %d", len(p.Fields), len(want))
	}
	for i, w := range want {
		f := p.Fields[i]
		if f.Name != w.name {
			t.Errorf("[%d] name: got %q want %q", i, f.Name, w.name)
		}
		if f.Type != w.typ {
			t.Errorf("[%d] type: got %v want %v", i, f.Type, w.typ)
		}
		if f.Offset != w.off {
			t.Errorf("[%d] offset: got %d want %d", i, f.Offset, w.off)
		}
	}
}

func TestBytesFixed(t *testing.T) {
	p := parse(t, `
protocol Wire {
    tag  u8
    key  bytes(32)
    id   u64
}`)
	if p.Fields[1].Type != TypeBytes {
		t.Fatalf("key type: got %v want bytes", p.Fields[1].Type)
	}
	if p.Fields[1].Size != 32 {
		t.Fatalf("key size: got %d want 32", p.Fields[1].Size)
	}
	if p.Fields[2].Offset != 1+32 {
		t.Fatalf("id offset: got %d want 33", p.Fields[2].Offset)
	}
}

// ── skip tag ─────────────────────────────────────────────────────────────────

func TestSkipField(t *testing.T) {
	p := parse(t, `
protocol Wire {
    pad  bytes(3) [skip]
    cmd  u64
}`)
	if !p.Fields[0].Skip {
		t.Fatal("pad should be skip")
	}
	// cmd offset still accounts for pad bytes
	if p.Fields[1].Offset != 3 {
		t.Fatalf("cmd offset: got %d want 3", p.Fields[1].Offset)
	}
	// FixedFields excludes skip
	ff := p.FixedFields()
	if len(ff) != 1 || ff[0].Name != "cmd" {
		t.Fatalf("FixedFields: %v", ff)
	}
}

// ── var field ────────────────────────────────────────────────────────────────

func TestVarField(t *testing.T) {
	p := parse(t, `
protocol Pkt {
    hdr   u32
    body  bytes(*) [var]
    mac   bytes(16) [trailing]
}`)
	vf := p.VarField()
	if vf == nil {
		t.Fatal("VarField() returned nil")
	}
	if vf.Name != "body" {
		t.Fatalf("var field name: %q", vf.Name)
	}
	if p.PrefixLen != 4 {
		t.Fatalf("PrefixLen: got %d want 4", p.PrefixLen)
	}
	if p.TrailingLen != 16 {
		t.Fatalf("TrailingLen: got %d want 16", p.TrailingLen)
	}
	if p.MinWireLen != 4+16 {
		t.Fatalf("MinWireLen: got %d want 20", p.MinWireLen)
	}
}

func TestBytesStarImpliesVar(t *testing.T) {
	p := parse(t, `
protocol P {
    data bytes(*)
}`)
	vf := p.VarField()
	if vf == nil || vf.Name != "data" {
		t.Fatal("bytes(*) should set Var=true")
	}
}

// ── trailing fields ───────────────────────────────────────────────────────────

func TestTrailingFields(t *testing.T) {
	p := parse(t, `
protocol P {
    mid  u64
    body bytes(*) [var]
    tag  u8
    sig  bytes(32)
}`)
	tf := p.TrailingFields()
	if len(tf) != 2 {
		t.Fatalf("trailing count: got %d want 2", len(tf))
	}
	if tf[0].Name != "tag" || tf[1].Name != "sig" {
		t.Fatalf("trailing names: %v %v", tf[0].Name, tf[1].Name)
	}
	if p.TrailingLen != 1+32 {
		t.Fatalf("TrailingLen: got %d want 33", p.TrailingLen)
	}
	// relative offsets from trailing start
	if tf[0].Offset-p.PrefixLen != 0 {
		t.Fatalf("tag relative offset: got %d want 0", tf[0].Offset-p.PrefixLen)
	}
	if tf[1].Offset-p.PrefixLen != 1 {
		t.Fatalf("sig relative offset: got %d want 1", tf[1].Offset-p.PrefixLen)
	}
}

// ── sevlet_wallet layout ─────────────────────────────────────────────────────

func TestSevletWalletLayout(t *testing.T) {
	src := `
protocol SevletWallet {
    pad        bytes(3)  [skip]
    command    u64
    mid        u64
    request_id u64
    order_id   u64
    amount     u64
    debit      u32
    credit     u32
    extra_data bytes(*)  [var]
    sig        bytes(32) [trailing]
}`
	p := parse(t, src)

	wantOffsets := map[string]int{
		"command":    3,
		"mid":        11,
		"request_id": 19,
		"order_id":   27,
		"amount":     35,
		"debit":      43,
		"credit":     47,
		"sig":        51, // = PrefixLen (relative 0 from trailing)
	}
	for _, f := range p.Fields {
		if exp, ok := wantOffsets[f.Name]; ok {
			if f.Offset != exp {
				t.Errorf("%s offset: got %d want %d", f.Name, f.Offset, exp)
			}
		}
	}
	if p.PrefixLen != 51 {
		t.Errorf("PrefixLen: got %d want 51", p.PrefixLen)
	}
	if p.TrailingLen != 32 {
		t.Errorf("TrailingLen: got %d want 32", p.TrailingLen)
	}
	if p.MinWireLen != 83 {
		t.Errorf("MinWireLen: got %d want 83", p.MinWireLen)
	}

	// sig relative offset = 0
	sig := p.TrailingFields()[0]
	if sig.Offset-p.PrefixLen != 0 {
		t.Errorf("sig relative offset: got %d want 0", sig.Offset-p.PrefixLen)
	}
}

// ── name helpers ──────────────────────────────────────────────────────────────

func TestNameHelpers(t *testing.T) {
	p := parse(t, `
protocol P {
    request_id u64
}`)
	f := p.Fields[0]
	if f.CamelCase() != "RequestId" {
		t.Errorf("CamelCase: %q", f.CamelCase())
	}
	if f.LowerCamel() != "requestId" {
		t.Errorf("LowerCamel: %q", f.LowerCamel())
	}
	if f.ConstName() != "REQUEST_ID" {
		t.Errorf("ConstName: %q", f.ConstName())
	}
}

// ── error cases ───────────────────────────────────────────────────────────────

func TestErrorNoProtocol(t *testing.T) {
	parseErr(t, ``)
}

func TestErrorMissingName(t *testing.T) {
	parseErr(t, `protocol {`)
}

func TestErrorUnknownType(t *testing.T) {
	parseErr(t, `
protocol P {
    x float32
}`)
}

func TestErrorBytesZeroSize(t *testing.T) {
	parseErr(t, `
protocol P {
    x bytes(0)
}`)
}

func TestErrorTwoVarFields(t *testing.T) {
	parseErr(t, `
protocol P {
    a bytes(*) [var]
    b bytes(*) [var]
}`)
}

func TestErrorUnknownTag(t *testing.T) {
	parseErr(t, `
protocol P {
    x u8 [optional]
}`)
}
