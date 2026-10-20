package com.vaultdrive.common.exception;

public record ApiError(
        int status,
        String error,
        String message
) {
    public ApiError {
        error = org.springframework.http.HttpStatus.valueOf(status).name();
    }

    public static void write(jakarta.servlet.http.HttpServletResponse response, int status, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValue(response.getWriter(), new ApiError(status, "", message));
    }
}
