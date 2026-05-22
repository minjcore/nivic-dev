package main

import (
	"fmt"
	"html/template"
	"net/http"
	"strings"
)

var merchantPageTmpl = template.Must(template.New("page").Funcs(template.FuncMap{
	"fmtPrice": func(v uint64) string {
		s := fmt.Sprintf("%d", v)
		var b strings.Builder
		n := len(s)
		for i, c := range s {
			if i > 0 && (n-i)%3 == 0 {
				b.WriteByte('.')
			}
			b.WriteRune(c)
		}
		return b.String()
	},
}).Parse(`<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>{{.Merchant.Name}}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#f5f6fa;color:#1a1a2e;min-height:100vh}
.header{background:linear-gradient(135deg,#4f46e5,#7c3aed);color:#fff;padding:24px 20px 32px;text-align:center}
.header h1{font-size:1.6rem;font-weight:700;margin-bottom:4px}
.header p{opacity:.85;font-size:.9rem}
.badge{display:inline-block;background:rgba(255,255,255,.2);border-radius:12px;padding:4px 12px;font-size:.75rem;margin-top:8px}
.container{max-width:480px;margin:0 auto;padding:0 16px 80px}
.card{background:#fff;border-radius:16px;padding:20px;margin-top:16px;box-shadow:0 1px 4px rgba(0,0,0,.08)}
.card h2{font-size:1rem;font-weight:600;color:#4f46e5;margin-bottom:14px;display:flex;align-items:center;gap:8px}
.menu-item{display:flex;justify-content:space-between;align-items:center;padding:12px 0;border-bottom:1px solid #f0f0f5;cursor:pointer;transition:background .15s;border-radius:8px;padding:10px 8px;margin:0 -8px}
.menu-item:last-child{border-bottom:none}
.menu-item:hover{background:#f5f3ff}
.menu-item .item-name{font-weight:500;font-size:.95rem}
.menu-item .item-desc{font-size:.78rem;color:#888;margin-top:2px}
.menu-item .item-price{font-weight:600;color:#4f46e5;white-space:nowrap;margin-left:12px}
.menu-item.selected{background:#ede9fe}
input[type=number],input[type=text]{width:100%;padding:12px 14px;border:2px solid #e5e7eb;border-radius:12px;font-size:1rem;outline:none;transition:border .2s;margin-bottom:12px}
input[type=number]:focus,input[type=text]:focus{border-color:#4f46e5}
.btn{width:100%;padding:14px;background:linear-gradient(135deg,#4f46e5,#7c3aed);color:#fff;border:none;border-radius:14px;font-size:1rem;font-weight:600;cursor:pointer;transition:opacity .2s;margin-top:4px}
.btn:hover{opacity:.9}
.btn:disabled{opacity:.5;cursor:not-allowed}
.btn-outline{background:#fff;border:2px solid #4f46e5;color:#4f46e5}
.btn-outline:hover{background:#f5f3ff}
.qr-section{display:none;text-align:center;padding-top:4px}
.qr-section.show{display:block}
#qr-canvas{margin:16px auto;display:inline-block}
.qr-amount{font-size:1.3rem;font-weight:700;color:#4f46e5;margin-bottom:4px}
.qr-note{font-size:.85rem;color:#888;margin-bottom:16px}
.points-result{margin-top:12px;padding:14px;background:#f0fdf4;border-radius:12px;border:1px solid #bbf7d0;display:none}
.points-result.show{display:block}
.points-big{font-size:1.5rem;font-weight:700;color:#16a34a}
.points-sub{font-size:.8rem;color:#666;margin-top:2px}
.empty{text-align:center;color:#aaa;padding:20px 0;font-size:.9rem}
</style>
</head>
<body>

<div class="header">
  <h1>{{.Merchant.Name}}</h1>
  {{if .Merchant.Address}}<p>📍 {{.Merchant.Address}}</p>{{end}}
  {{if .Merchant.Website}}<p style="margin-top:6px"><a href="{{.Merchant.Website}}" style="color:rgba(255,255,255,.9);text-decoration:underline;font-size:.85rem" target="_blank" rel="noopener">🌐 {{.Merchant.Website}}</a></p>{{end}}
  <span class="badge">Thanh toán Saving</span>
</div>

<div class="container">

{{if .MenuItems}}
<div class="card">
  <h2>🍽 Menu</h2>
  {{range .MenuItems}}
  <div class="menu-item" onclick="selectItem(this, {{.Price}}, '{{js .Name}}')">
    <div>
      <div class="item-name">{{.Name}}</div>
      {{if .Description}}<div class="item-desc">{{.Description}}</div>{{end}}
    </div>
    <div class="item-price">{{fmtPrice .Price}}₫</div>
  </div>
  {{end}}
</div>
{{end}}

<div class="card">
  <h2>💳 Tạo QR thanh toán</h2>
  <div id="pay-form">
    <input type="number" id="amount" placeholder="Số tiền (₫)" min="1000" step="1000">
    <input type="text" id="note" placeholder="Ghi chú (tuỳ chọn)">
    <button class="btn" onclick="createOrder()">Tạo QR thanh toán</button>
  </div>
  <div class="qr-section" id="qr-section">
    <div class="qr-amount" id="qr-amount-text"></div>
    <div class="qr-note" id="qr-note-text"></div>
    <div id="qr-canvas"></div>
    <button class="btn btn-outline" onclick="resetPay()" style="margin-top:8px">Tạo QR mới</button>
  </div>
</div>

<div class="card">
  <h2>⭐ Điểm tích luỹ của bạn</h2>
  <input type="number" id="uid-input" placeholder="Nhập ID tài khoản Saving">
  <button class="btn btn-outline" onclick="checkPoints()">Kiểm tra điểm</button>
  <div class="points-result" id="points-result">
    <div class="points-big" id="points-value"></div>
    <div class="points-sub" id="points-sub"></div>
  </div>
</div>

</div>

<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js" crossorigin="anonymous"></script>
<script>
const MID = {{.Merchant.MID}};

function fmtVND(n) {
  return n.toString().replace(/\B(?=(\d{3})+(?!\d))/g, '.') + '₫';
}

function selectItem(el, price, name) {
  document.querySelectorAll('.menu-item').forEach(e => e.classList.remove('selected'));
  el.classList.add('selected');
  document.getElementById('amount').value = price;
  document.getElementById('note').value = name;
}

async function createOrder() {
  const amount = parseInt(document.getElementById('amount').value);
  const note = document.getElementById('note').value.trim();
  if (!amount || amount < 1000) { alert('Vui lòng nhập số tiền (tối thiểu 1.000₫)'); return; }

  const btn = document.querySelector('#pay-form .btn');
  btn.disabled = true; btn.textContent = 'Đang tạo…';

  try {
    const res = await fetch('/public/' + MID + '/order', {
      method: 'POST',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify({amount, note})
    });
    const data = await res.json();
    if (!res.ok) { alert(data.error || 'Lỗi tạo đơn'); return; }

    document.getElementById('qr-amount-text').textContent = fmtVND(amount);
    document.getElementById('qr-note-text').textContent = note || '';
    document.getElementById('qr-canvas').innerHTML = '';
    new QRCode(document.getElementById('qr-canvas'), {
      text: data.qr_url, width: 220, height: 220,
      colorDark: '#4f46e5', colorLight: '#fff',
      correctLevel: QRCode.CorrectLevel.M
    });
    document.getElementById('pay-form').style.display = 'none';
    document.getElementById('qr-section').classList.add('show');
  } catch(e) {
    alert('Lỗi kết nối');
  } finally {
    btn.disabled = false; btn.textContent = 'Tạo QR thanh toán';
  }
}

function resetPay() {
  document.getElementById('qr-section').classList.remove('show');
  document.getElementById('pay-form').style.display = '';
  document.getElementById('amount').value = '';
  document.getElementById('note').value = '';
  document.querySelectorAll('.menu-item').forEach(e => e.classList.remove('selected'));
}

async function checkPoints() {
  const uid = parseInt(document.getElementById('uid-input').value);
  if (!uid) { alert('Vui lòng nhập ID tài khoản'); return; }
  try {
    const res = await fetch('/merchants/' + MID + '/loyalty/' + uid);
    const data = await res.json();
    if (!res.ok) { alert(data.error || 'Không tìm thấy'); return; }
    const el = document.getElementById('points-result');
    document.getElementById('points-value').textContent = data.points + ' điểm';
    document.getElementById('points-sub').textContent =
      'Tương đương ' + fmtVND(data.value_vnd) + ' • Tích 1 điểm / 1.000₫';
    el.classList.add('show');
  } catch(e) {
    alert('Lỗi kết nối');
  }
}
</script>
</body>
</html>
`))

func renderMerchantPage(w http.ResponseWriter, m *Merchant, items []MenuItem) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = merchantPageTmpl.Execute(w, struct {
		Merchant  *Merchant
		MenuItems []MenuItem
	}{m, items})
}
