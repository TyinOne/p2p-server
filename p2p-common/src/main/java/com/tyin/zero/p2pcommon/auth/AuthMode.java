package com.tyin.zero.p2pcommon.auth;

/**
 * 认证模式枚举
 */
public enum AuthMode {

    /**
     * 共享密钥模式（简单，适合开发/测试）
     */
    SHARED_KEY,

    /**
     * RSA密钥对模式（安全，适合生产环境）
     */
    RSA_KEYPAIR;
}
