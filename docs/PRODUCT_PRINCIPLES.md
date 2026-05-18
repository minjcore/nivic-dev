# Product principles / Nguyên tắc sản phẩm

This page states how we design and **talk about** the platform: what is promised to users and partners, and what belongs to engineering detail versus product truth.

Trang này mô tả cách chúng ta thiết kế và **truyền đạt** nền tảng: cam kết với người dùng và đối tác là gì, và đâu là chi tiết kỹ thuật đối chiếu với **sự thật sản phẩm**.

---

## 1. One trust axis / Một trục niềm tin

**EN:** Money and payment state flow through a **single, auditable path**: verify the wire payload, append to the WAL, then project into ledgers and journals. Marketing may highlight speed; the product promise is **correctness and traceability**, not a race to unverifiable claims.

**VI:** Tiền và trạng thái thanh toán đi qua **một đường có thể đối soát**: xác thực gói wire, ghi WAL, rồi chiếu ra ledger và journal. Marketing có thể nhấn “nhanh”; cam kết sản phẩm là **đúng và truy vết được**, không phải đua khẳng định chưa đo.

---

## 2. “Want” close to “have” / “Muốn” gần “có”

**EN:** **Parked orders** and **user confirm** shrink the psychological gap between intent and settlement. UX should feel like one tap after the merchant has prepared the order—not a long chain of unknown spinners without state.

**VI:** **Đơn parked** và **user confirm** thu hẹp khoảng cách giữa ý định và settle. Trải nghiệm là **một chạm** sau khi merchant đã chuẩn bị đơn—không phải chuỗi chờ dài không có trạng thái rõ.

---

## 3. Many stalls, one ruleset / Nhiều sạp, một luật chơi

**EN:** **Merchants (and future banks-as-`mid`)** share the **same wire protocol and idempotency rules**; they differ by **policy, quotas, and secrets**. The platform sells **reliable rails and tenancy**, not one-off bespoke pipes per partner.

**VI:** **Merchant (và sau này bank như `mid`)** dùng **chung wire và luật idempotency**; khác nhau ở **policy, quota, secret**. Nền tảng bán **đường ray tin cậy và đa tenant**, không phải ống tùy hứng từng đối tác.

---

## 4. Sharp boundaries / Ranh giới rõ

**EN:**

- **Core (wallet path):** source of truth for accepted payloads and balances after settlement. See [ADR 001: Wire format](adr/001-sevlet-wallet-wire.md).
- **Search (Meilisearch):** discovery only; never the financial source of truth. See [ADR 002: Search boundary](adr/002-search-boundary-meilisearch.md).
- **Analytics:** WAL → stream → warehouse (e.g. ClickHouse) for reporting; separate from hot payment path. See [WAL → analytics](analytics/wal-to-clickhouse.md).

**VI:**

- **Core (luồng ví):** nguồn sự thật cho payload đã chấp nhận và số dư sau settle. Xem [ADR 001](adr/001-sevlet-wallet-wire.md).
- **Search (Meilisearch):** chỉ discovery; **không** là sự thật tài chính. Xem [ADR 002](adr/002-search-boundary-meilisearch.md).
- **Analytics:** WAL → stream → kho OLAP; tách khỏi hot path thanh toán. Xem [WAL → analytics](analytics/wal-to-clickhouse.md).

---

## 5. The `extraData` “small bag” / “Túi nhỏ” `extraData`

**EN:** **`extraData` is opaque** on the hot path unless a command requires structured parsing (e.g. confirm). It may be **empty**. Size is **capped** at ingress. Merchants own the meaning inside the bag; the platform guarantees **integrity** (MAC covers `command` through `extraData`) and **limits**, not every business schema.

**VI:** **`extraData` là opaque** trên hot path trừ khi lệnh bắt parse (ví dụ confirm). Có thể **rỗng**. Kích thước **bị giới hạn** lúc vào cổng. Merchant định nghĩa nội dung “túi”; nền tảng đảm bảo **toàn vẹn** (MAC phủ `command`…`extraData`) và **giới hạn**, không ôm mọi schema nghiệp vụ.

---

## 6. Measurable speed / “Nhanh” phải đo được

**EN:** Public messaging should tie “faster” to **observable outcomes**: time to accept, time to confirm-to-settled, fewer perceived stalls. Avoid tying brand claims to **implementation trivia** (e.g. exact byte counts, cache lines) unless backed by reproducible benchmarks for the stated workload.

**VI:** Truyền thông “nhanh” nên gắn **kết quả quan sát được**: thời gian chấp nhận, confirm→settle, ít chờ vô định. Tránh neo thương hiệu vào **chi tiết triển khai** (số byte, cache line) trừ khi có **benchmark tái lập** cho tải đã nói.

---

## 7. Stack honesty / Thành thật về stack

**EN:** The reference wallet ingress is **Java (Jakarta Servlet)** and **binary wire**, not Go. Alternative stacks are optional accelerators, not a rewrite requirement for the product story.

**VI:** Cổng ví tham chiếu là **Java (Servlet)** và **wire nhị phân**, không bắt buộc Go. Stack khác chỉ là **tùy chọn tăng tốc**, không phải điều kiện để câu chuyện sản phẩm đứng vững.

---

## 8. Delivery risk first / Giảm rủi ro delivery trước

**EN:** **Ship something correct before chasing novelty.** Prefer stacks the team can run, debug, and hire for (here: Java + Servlet + JDBC + Postgres). That cuts schedule risk, incident mean-time-to-repair, and bus factor. Micro-optimizations or alternate languages are **optional layers** once the contract (wire, WAL, ledger) is stable—not prerequisites for the product narrative.

**VI:** **Giao được thứ đúng trước khi đuổi theo “vibe” công nghệ mới.** Ưu tiên stack team **chạy được, log được, tuyển được** (ở đây: Java + Servlet + JDBC + Postgres) → **giảm rủi ro delivery**, MTTR, và bus factor. Tối ưu nhỏ hay đổi ngôn ngữ chỉ là **lớp tùy chọn** khi hợp đồng sản phẩm (wire, WAL, ledger) đã ổn—không phải điều kiện để câu chuyện nền tảng đứng vững.

---

## 9. Book-keeping / Sổ cái (kế toán kép)

**EN:** After a payment is **accepted and settled** on the non–order-payment path, **double-entry book-keeping** is recorded: `wallet_journal_entry` (header) and `wallet_journal_line` (balanced debit/credit lines for the wire `amount`). That is the **accounting projection** of value movement—not the search index, not Meilisearch, and not raw `extraData` semantics. `payment_ledger` covers the **order-payment / intent** phase and may hold `debit`/`credit` only after settle; see schema comments in the repo.

**VI:** Sau khi thanh toán **được chấp nhận và settle** (luồng không phải order-payment tức thời), hệ thống ghi **kế toán kép**: `wallet_journal_entry` (đầu bút) và `wallet_journal_line` (dòng nợ/có cân đối theo `amount` trên wire). Đó là **bút toán / sổ cái** phản ánh dòng giá trị—**không** phải index tìm kiếm, **không** phải Meilisearch, và **không** phải nghĩa nghiệp vụ thô của `extraData`. `payment_ledger` phục vụ **phase order-payment / intent**; cột `debit`/`credit` chỉ đầy đủ sau settle—xem comment schema trong repo.

---

## 10. Where “ledgers data” lives / Dữ liệu sổ cái nằm ở đâu

**EN:** There is **no** single table named `ledgers_data` in this repo. Use these **Postgres** artifacts (see [schema.sql](../java/src/main/resources/db/schema.sql) and `db/schema/*.sql`):

| Table / artifact | Role |
|------------------|------|
| `wallet_ledger` | One append-only row per **accepted** wallet message (projection after WAL on immediate-settle path). |
| `payment_ledger` | Order-payment **intent** row; **`PRIMARY KEY (mid, request_id)`** enforces **idempotency at ledger row level** (no duplicate intent for the same keys). Upsert after settle; `intent_status`, TTL, `confirm_challenge`. |
| `wallet_journal_entry` | Double-entry **voucher header** per settled transfer. |
| `wallet_journal_line` | Two balanced lines (debit / credit accounts, amounts). |
| `wallet_account_hold` | Soft hold for intents (debit account + amount) when applicable. |
| `wallet_idempotency` | **First-claim gate** before WAL/ledger writes: `INSERT … ON CONFLICT DO NOTHING` on `(mid, request_id)`; order-payment mids also enforce **stored `order_id`** on retries. Complements `payment_ledger` PK; see [`JdbcIdempotencyGate`](../java/src/main/java/dev/nivic/sevlet/idempotency/JdbcIdempotencyGate.java). |

**VI:** Repo **không** có bảng tên `ledgers_data`. Dữ liệu sổ / projection nằm ở các bảng **Postgres** sau (xem [schema.sql](../java/src/main/resources/db/schema.sql) và `db/schema/*.sql`):

| Bảng | Vai trò |
|------|--------|
| `wallet_ledger` | Một dòng append-only mỗi tin ví **đã chấp nhận** (chiếu sau WAL, luồng settle ngay). |
| `payment_ledger` | Dòng **intent** order-payment; **`PRIMARY KEY (mid, request_id)`** = **idempotency trực tiếp trên bảng** (không hai intent trùng khóa). Upsert sau settle; `intent_status`, TTL, `confirm_challenge`. |
| `wallet_journal_entry` | **Đầu bút** kế toán kép mỗi lần transfer settle. |
| `wallet_journal_line` | Hai dòng cân đối (tài khoản nợ/có, số tiền). |
| `wallet_account_hold` | Hold mềm cho intent (khi có). |
| `wallet_idempotency` | **Cổng claim trước** khi ghi WAL/ledger: `INSERT … ON CONFLICT` theo `(mid, request_id)`; mid order-payment còn khóa **`order_id`** khi retry. Bổ sung cho PK `payment_ledger`; xem [`JdbcIdempotencyGate`](../java/src/main/java/dev/nivic/sevlet/idempotency/JdbcIdempotencyGate.java). |

If you need a **named** `ledgers_data` for BI, define it as a **VIEW** or export job over these tables—do not treat it as a second source of truth.

Nếu cần tên **`ledgers_data`** cho BI, hãy định nghĩa là **VIEW** hoặc job export trên các bảng trên—**không** dùng như nguồn sự thật thứ hai.

---

## 11. Product surfaces: Super App and SavingApp / Bề mặt sản phẩm: Super App và SavingApp

**EN:** User journeys are grouped into **named surfaces** that still obey §1–§10.

**Super App** is the **host shell**: home and navigation, **merchant stalls** (§3), catalog and profile **discovery**, and the in-app **search box** (see [ADR 002](adr/002-search-boundary-meilisearch.md)). It owns **shell UX and hand-offs into mini-apps** (e.g. SavingApp, partner stalls); it does **not** own balances, holds, or payment state—those remain on the **wallet path** (§1, §4). Search and browse indices are **eventually consistent**; never cite them as proof of funds or settlement.

**SavingApp** is the **savings lane**: goals, pots, nudges, and scheduling UX. Money still flows only through the **wallet path** (wire → WAL → ledgers / journals); SavingApp must not imply a second, shadow “savings ledger” unless that is an explicit, schema-backed product decision communicated to partners.

**VI:** Hành trình người dùng được gom theo **bề mặt có tên**, vẫn tuân §1–§10.

**Super App** là **vỏ host**: trang chủ và điều hướng, **sạp merchant** (§3), **discovery** danh mục / hồ sơ, và **ô tìm** trong app (xem [ADR 002](adr/002-search-boundary-meilisearch.md)). Super App nắm **UX lớp vỏ và bàn giao người dùng sang mini-app** (ví dụ SavingApp, sạp đối tác); **không** nắm số dư, hold, hay trạng thái thanh toán—các thứ đó vẫn thuộc **luồng ví** (§1, §4). Index tìm kiếm / duyệt **có thể trễ**; **không** dùng làm chứng cứ tiền hay đã settle.

**SavingApp** là **lane tiết kiệm**: mục tiêu, hũ/nhóm, nhắc nhở, lịch nạp. Tiền vẫn chỉ đi qua **luồng ví** (wire → WAL → ledger / journal); SavingApp **không** được gợi ý một “sổ tiết kiệm” song song nếu chưa có quyết định sản phẩm + schema rõ ràng cho đối tác.

---

## How to use this doc / Cách dùng tài liệu

**EN:** Use it for onboarding, partner decks, and **copy review**: if a sentence contradicts a principle here, fix the sentence or update this page with a dated rationale.

**VI:** Dùng cho onboarding, deck đối tác, và **duyệt copy**: nếu câu nào mâu thuẫn nguyên tắc ở đây, sửa câu đó hoặc cập nhật trang này kèm lý do và thời điểm.
