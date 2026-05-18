# Downstream event contract (Core → analytics / partners)

Mục tiêu: mọi bản ghi gửi ra **queue / object store / warehouse** đều có **phiên bản schema cố định**, đổi cột **không gãy** consumer cũ (chỉ **thêm** field hoặc **tăng** `schema_version`).

## 1. Envelope (bắt buộc mọi message)

| Field | Type | Mô tả |
|--------|------|--------|
| `schema_version` | `integer` | Bắt đầu từ **1**. Breaking change → tăng số (consumer register handler theo version). |
| `event_type` | `string` | Ví dụ: `wallet.payload_accepted`, `wallet.order_intent_created`, `wallet.confirm_settled`. |
| `occurred_at` | `string` | RFC 3339 UTC, thời điểm Core ghi nhận (hoặc append WAL), ví dụ `2026-05-12T10:15:30.123Z`. |
| `payload` | `object` | Dữ liệu nghiệp vụ; quy tắc từng version bên dưới. |

**Không** đổi tên / xóa field trong `payload` của một `schema_version` đã publish. Chỉ:

- **Thêm** field optional trong cùng version (consumer ignore unknown keys — JSON), hoặc
- Tăng `schema_version` và document mapping.

## 2. `payload` cho `schema_version` = 1 (`wallet.payload_accepted`)

Khớp decode từ wire (`SevletWalletPayload` / `SevletWalletCodec`). Số wire là **u64 trong long** / u32: xuống JSON dùng **string thập phân** để tránh mất precision ở JavaScript và một số JSON parser.

| Field | Type | Ghi chú |
|--------|------|--------|
| `mid` | `string` | decimal unsigned |
| `request_id` | `string` | |
| `order_id` | `string` | |
| `command` | `string` | opcode wire |
| `input_command` | `string` | nhãn debug ổn định (ví dụ từ `WalletInputCommands`) |
| `amount` | `string` | minor units |
| `debit` | `string` | decimal unsigned |
| `credit` | `string` | |
| `extra_data` | `string` | **Base64**; policy PII: có thể omit hoặc thay bằng `extra_data_sha256` |
| `sig` | `string` | **hex** 64 ký tự (32 byte) |

Optional (nên có khi pipeline cho phép):

| Field | Type | Ghi chú |
|--------|------|--------|
| `currency_code` | `string` | ISO 4217, ví dụ `USD` |
| `raw_body_sha256` | `string` | hex — idempotent dedup ở sink |
| `wal_sequence` | `integer` hoặc `string` | nếu emit từ WAL có thứ tự |

## 3. Định dạng vận chuyển

- **NDJSON** (một object JSON / dòng): đơn giản cho S3 + Athena / batch.
- **Kafka / Pulsar**: value = một JSON object (hoặc Avro/Protobuf — registry schema id tách khỏi `schema_version` trong payload nếu dùng Confluent wire format).
- **Avro / Protobuf**: dùng **schema registry** + field `schema_version` trong **business header** (metadata) vẫn nên giữ để routing logic không phụ thuộc registry.

## 4. Tiến hóa (evolution)

1. **Patch (cùng `schema_version`)**: chỉ thêm key mới, optional; consumer cũ bỏ qua.
2. **Minor** (JSON): vẫn có thể gói trong cùng version nếu chỉ additive — hoặc tăng version cho rõ ràng.
3. **Major**: đổi semantics / đổi tên field bắt buộc → `schema_version` + 1, chạy song song consumer hai version trong giai đoạn chuyển.

## 5. Liên hệ code Java hiện tại

- Decode payload: `SevletWalletCodec` → `SevletWalletPayload`.
- JSON debug (không phải contract downstream): `WalletPayloadJson.format` — khi implement producer, map sang **envelope** + quy tắc string ở trên.

File JSON Schema mẫu: [`schemas/wallet_core_event_v1.schema.json`](schemas/wallet_core_event_v1.schema.json).
