package dev.nivic.coa.api;

/** HTTP status + JSON body produced by {@link FundFlowApi}. */
public record ApiResponse(int status, String json) {

  public static ApiResponse ok(String json)   { return new ApiResponse(200, json); }
  public static ApiResponse created(String j)  { return new ApiResponse(201, j); }

  public static ApiResponse error(int status, String code, String message) {
    return new ApiResponse(status,
        "{\"error\":" + MiniJson.str(code) + ",\"message\":" + MiniJson.str(message) + "}");
  }
}
