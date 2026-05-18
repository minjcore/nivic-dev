# ADR 002: Search boundary với Meilisearch

## Status

Accepted

## Context

Super App cần **ô tìm kiếm** (merchant, sản phẩm, hồ sơ công khai). Thanh toán và intent đã có **nguồn sự thật** riêng (WAL, `payment_ledger`, journal). Cần ranh giới rõ để **Meilisearch** không bị nhầm là ledger hoặc nguồn chứng cứ tài chính.

## Decision

Dùng **Meilisearch** chỉ cho **discovery / catalog search** trong UI. Mọi trạng thái tiền, idempotency, và chứng cứ pháp lý vẫn đi qua **WAL + SQL** (và pipeline **ClickHouse** cho OLAP như đã mô tả riêng).

## 1. Phạm vi Meilisearch (in-scope)

| Mục | Mô tả |
|-----|--------|
| **Index** | Thực thể discovery: `merchant`, `product`, `public_profile`, v.v. — chỉ field **được phép lộ** công khai. |
| **Query** | Full-text, typo-tolerant; filter theo `tenant_id` và/hoặc `mid` (khi mapping rõ với domain). |
| **Kết quả** | Trả về **id ổn định** (`surface_id`, `mid`, SKU id, …) để app gọi **API thanh toán / intent** tiếp theo. Hit search **không** đồng nghĩa đã thanh toán hay đã hold tiền. |

## 2. Ranh giới cứng (out-of-scope / forbidden)

- **Không** đưa vào index: toàn bộ `extraData` wire, `secret_key`, HMAC material, số dư tài khoản, số hold thô, `confirm_challenge`.
- **Không** dùng Meilisearch làm nguồn sự thật cho: idempotency `(mid, request_id)`, trạng thái intent, journal lines. Các luồng đó vẫn là [WalService](../../java/src/main/java/dev/nivic/payment/WalService.java), [`payment_ledger`](../../java/src/main/resources/db/schema/06_payment_ledger.sql), và journal/wallet persistence tương ứng.
- **Analytics / funnel** theo hướng [WAL → ClickHouse](../analytics/wal-to-clickhouse.md). Meili **không** thay ClickHouse cho OLAP; chỉ dùng dữ liệu đã **ẩn danh / tổng hợp** từ pipeline nếu có nhu cầu search trên metric (thường không cần).

## 3. Đồng bộ (boundary với Core / DB)

- **Hướng một chiều**: Postgres hoặc service catalog (source of truth cho listing) → event hoặc job định kỳ → Meilisearch Documents API. Cập nhật theo **document id** idempotent; retry an toàn.
- **Không** ghi ngược từ Meili vào ledger hoặc vào trạng thái tiền.
- **SLA**: index có thể **lag** vài giây đến vài phút; luồng thanh toán **không** phụ thuộc độ fresh của Meili. Giá / khả dụng “cứng” cho checkout lấy từ **API catalog hoặc intent snapshot**, không từ hit search cuối cùng nếu risk mismatch.

## 4. Multi-tenant

- Mỗi document có **`tenant_id`** (hoặc tương đương rõ ràng). Mọi query từ BFF/gateway **bắt buộc** filter theo tenant của phiên đăng nhập (hoặc tách index / API key theo tenant nếu vận hành chọn mô hình đó).
- Review facet/filter để tránh **cross-tenant leak** do cấu hình filter sai.

## 5. So sánh vai trò

| Hệ thống | Vai trò |
|----------|---------|
| **Meilisearch** | Discovery, ô tìm kiếm Super App |
| **WAL + SQL ledger / journal** | Sự thật thanh toán và đối soát |
| **ClickHouse** (pipeline tùy triển khai) | OLAP, báo cáo, funnel theo tenant |

## Consequences

- Team search và team core có **contract** rõ: id từ Meili chỉ là **định hướng UI**, không phải chứng từ.
- Thay đổi schema index không ảnh hưởng wire wallet; ngược lại đổi wire không bắt buộc đổi index search.

## Out of scope (repo hiện tại)

- Không bật sẵn Docker Compose cho Meilisearch trong bước ADR này.
- Không thay đổi [`SevletWalletCodec`](../../java/src/main/java/dev/nivic/sevlet/SevletWalletCodec.java) hay servlet ingest.
