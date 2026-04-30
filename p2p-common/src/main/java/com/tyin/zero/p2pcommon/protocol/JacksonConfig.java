package com.tyin.zero.p2pcommon.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 统一的 Jackson ObjectMapper 配置
 */
public final class JacksonConfig {

    private static final ObjectMapper INSTANCE = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JacksonConfig() {}

    public static ObjectMapper objectMapper() {
        return INSTANCE;
    }
}
