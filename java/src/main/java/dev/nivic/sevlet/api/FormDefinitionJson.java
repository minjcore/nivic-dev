package dev.nivic.sevlet.api;

import java.nio.charset.StandardCharsets;

/**
 * Shared JSON form definition: merchant admin (demo). XML or DB can map to the same shape.
 *
 * <p>Field types: {@code text}, {@code number}, {@code select}, {@code textarea}, and
 * {@code custom-field} — a grouped block with nested {@code fields} for tenant-specific extensions.
 * The UI submits nested values under {@code customFields} in the POST body.
 */
public final class FormDefinitionJson {

  private FormDefinitionJson() {}

  /** Core field schema (embedded in {@link FormManifestServlet} and served by {@link FormSchemaServlet}). */
  public static final byte[] SCHEMA_JSON =
      """
      {
        "formId": "merchant-draft",
        "title": "Quản lý Merchants",
        "fields": [
          {
            "id": "merchant_code",
            "type": "text",
            "label": "Mã merchant (nội bộ / MID)",
            "placeholder": "VD: MRC-001",
            "required": true,
            "maxLength": 64
          },
          {
            "id": "legal_name",
            "type": "text",
            "label": "Tên pháp lý",
            "placeholder": "Công ty TNHH …",
            "required": true,
            "maxLength": 256
          },
          {
            "id": "trade_name",
            "type": "text",
            "label": "Tên giao dịch (DBA)",
            "placeholder": "Tên hiển thị trên POS / hoá đơn",
            "required": false,
            "maxLength": 256
          },
          {
            "id": "mcc",
            "type": "number",
            "label": "MCC (Merchant Category Code)",
            "min": 1000,
            "max": 9999,
            "required": true
          },
          {
            "id": "country",
            "type": "select",
            "label": "Quốc gia / vùng",
            "required": true,
            "default": "",
            "options": [
              { "value": "", "label": "— Chọn —" },
              { "value": "VN", "label": "Việt Nam" },
              { "value": "US", "label": "Hoa Kỳ" },
              { "value": "SG", "label": "Singapore" },
              { "value": "GB", "label": "Anh" },
              { "value": "OTHER", "label": "Khác" }
            ]
          },
          {
            "id": "status",
            "type": "select",
            "label": "Trạng thái onboarding",
            "required": true,
            "default": "pending_review",
            "options": [
              { "value": "pending_review", "label": "Chờ duyệt" },
              { "value": "active", "label": "Đang hoạt động" },
              { "value": "suspended", "label": "Tạm khoá" }
            ]
          },
          {
            "id": "contact_email",
            "type": "text",
            "label": "Email liên hệ (risk / support)",
            "placeholder": "merchant@example.com",
            "required": true,
            "maxLength": 254
          },
          {
            "id": "contact_phone",
            "type": "text",
            "label": "Điện thoại liên hệ",
            "placeholder": "+84 …",
            "required": false,
            "maxLength": 32
          },
          {
            "id": "settlement_notes",
            "type": "textarea",
            "label": "Ghi chú thanh toán / KYC",
            "placeholder": "Số tài khoản, ngân hàng, ghi chú nội bộ…",
            "rows": 4,
            "required": false
          },
          {
            "id": "merchant_extensions",
            "type": "custom-field",
            "label": "Trường tùy chỉnh (tenant)",
            "description": "Schema mở rộng theo Mc — không sửa core fields phía trên.",
            "fields": [
              {
                "id": "partner_ref",
                "type": "text",
                "label": "Mã đối tác / referral",
                "placeholder": "VD: AGT-HCM-01",
                "required": false,
                "maxLength": 64
              },
              {
                "id": "onboarding_tier",
                "type": "select",
                "label": "Gói onboarding",
                "required": false,
                "default": "standard",
                "options": [
                  { "value": "standard", "label": "Standard" },
                  { "value": "priority", "label": "Priority" },
                  { "value": "enterprise", "label": "Enterprise" }
                ]
              },
              {
                "id": "internal_tags",
                "type": "textarea",
                "label": "Tags nội bộ (CSV)",
                "placeholder": "qr-pay, stall, pilot",
                "rows": 2,
                "required": false,
                "maxLength": 512
              }
            ]
          }
        ]
      }
      """
          .getBytes(StandardCharsets.UTF_8);
}
