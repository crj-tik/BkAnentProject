package com.bkanent.agent.studio;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.rag.RagException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = StudioController.class)
public class StudioExceptionHandler {
    // Without this log the degraded-request trigger is invisible in deployed jars (KI-126).
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(StudioExceptionHandler.class);

    @ExceptionHandler(StudioException.class)
    public ResponseEntity<ApiResponse<Void>> studio(StudioException exception) {
        return ResponseEntity.status(exception.status()).body(ApiResponse.fail(exception.code(), exception.getMessage()));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> storage(DataAccessException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.fail("STUDIO_STORAGE_UNAVAILABLE", "工作记录暂时无法读取或保存，请稍后重试"));
    }
    @ExceptionHandler(RagException.class)
    public ResponseEntity<ApiResponse<Void>> source(RagException exception) {
        HttpStatus status = switch (exception.code()) {
            case AUTHENTICATION_FAILED, AUTHENTICATION_EXPIRED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case INVALID_ARGUMENT, RESOURCE_SCOPE_TOO_LARGE -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(exception.code().name(), "来源访问或依赖状态发生变化，请重新核对授权与范围"));
    }
    @ExceptionHandler(com.bkanent.agent.security.AgentPrincipalException.class)
    public ResponseEntity<ApiResponse<Void>> principal(Exception exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail("AUTH_401", "当前登录状态已失效，请重新登录"));
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_ARGUMENT", "请求格式无效，请核对后重试"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(Exception exception) {
        LOGGER.warn("studio request degraded", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.fail("STUDIO_DEPENDENCY_UNAVAILABLE", "本次操作暂未取得可靠结果，请查询原请求并核对依赖状态"));
    }
}
