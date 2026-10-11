package com.bitforum.common;

public class Result<T> {
    private int code;           //状态码：200 成功，400 失败
    private String message;     //提示信息
    private T data; //返回数据(泛型，可以是User、Article、List任意类型)
    
    //成功时调用
    public static <T> Result<T> ok(String message, T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.message = message;
        r.data = data;
        return r;
    }

    //失败时调用
    public static <T> Result<T> fail(String message) {
        return fail(400, message);
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        r.data = null;
        return r;
    }

    //getter
    public int getCode() {
        return code;
    }
    public String getMessage() {
        return message;
    }
    public T getData() {
        return data;
    }
}
