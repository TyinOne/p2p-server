package com.tyin.zero.p2pcommon.auth;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA密钥对生成工具
 */
public class KeyPairGenerator {

    /**
     * 生成RSA密钥对（2048位）
     */
    public static KeyPair generateKeyPair() throws Exception {
        java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        return keyGen.generateKeyPair();
    }

    /**
     * 将公钥转换为Base64字符串
     */
    public static String publicKeyToBase64(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * 将私钥转换为Base64字符串
     */
    public static String privateKeyToBase64(PrivateKey privateKey) {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    /**
     * 从Base64字符串解码公钥
     */
    public static PublicKey base64ToPublicKey(String publicKeyBase64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(keySpec);
    }
}
