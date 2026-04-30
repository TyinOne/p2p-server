package com.tyin.zero.p2pcommon.auth;

/**
 * 认证结果
 *
 * @param success      是否认证成功
 * @param clientId     客户端ID
 * @param errorMessage 错误消息（认证失败时）
 */
public record AuthResult(boolean success, String clientId, String errorMessage) {

    /**
     * 创建成功结果
     */
    public static AuthResult success(String clientId) {
        return new AuthResult(true, clientId, null);
    }

    /**
     * 创建失败结果
     */
    public static AuthResult failure(String errorMessage) {
        return new AuthResult(false, null, errorMessage);
    }
}
