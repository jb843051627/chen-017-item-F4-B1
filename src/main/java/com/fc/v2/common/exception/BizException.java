package com.fc.v2.common.exception;

/**
 * 业务规则异常：状态机越档、冻住卡被改/删/退、查重撞号等
 * “按规矩不能办”的情形统一抛此异常，由全局解析器转成带中文原因的 JSON，
 * 不再让前端只收到一句没头没尾的“操作失败”。
 *
 * @author fuce
 * @date 2026-10-02
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BizException(String message) {
        super(message);
    }
}
