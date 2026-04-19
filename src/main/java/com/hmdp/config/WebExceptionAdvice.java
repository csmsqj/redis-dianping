package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)

    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail(e.getMessage().toString());
    }
    //IllegalArgumentException
    @ExceptionHandler(IllegalArgumentException.class)
    public Result handleRuntimeException(IllegalArgumentException e) {
        log.error("参数异常：{}", e.getMessage());
        String message = e.getMessage();
        return Result.fail(message);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public Result handleDuplicateKeyException(DuplicateKeyException e) {
        log.error("数据库异常：{}", e.getMessage());
        String message = e.getMessage();
        return Result.fail(message);
    }


}
