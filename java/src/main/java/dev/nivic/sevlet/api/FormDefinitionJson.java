package dev.nivic.sevlet.api;

import java.nio.charset.StandardCharsets;

/** Shared JSON form definition: merchant admin (demo). XML or DB can map to the same shape. */
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
          }
        ]
      }
      """
          .getBytes(StandardCharsets.UTF_8);
}
