package com.bitforum.exception;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import com.bitforum.common.Result;

@RestControllerAdvice
public class ResultHttpStatusAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(
            MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof Result<?> result && result.getCode() >= 400 && result.getCode() <= 599) {
            response.setStatusCode(HttpStatusCode.valueOf(result.getCode()));
        }
        return body;
    }
}
