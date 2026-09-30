package com.orbit.backend.database;

import com.orbit.backend.common.exception.ApiException;
import com.orbit.backend.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Answers 503 DATABASE_NOT_READY for every API that needs the database until it has been provisioned. */
@Component
@RequiredArgsConstructor
public class DatabaseReadyInterceptor implements HandlerInterceptor {

    private final DatabaseProvisioner provisioner;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || provisioner.isReady()) {
            return true;
        }
        throw new ApiException(ErrorCode.DATABASE_NOT_READY, provisioner.getMessage());
    }
}
