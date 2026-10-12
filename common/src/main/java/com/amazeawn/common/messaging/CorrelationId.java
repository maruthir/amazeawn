package com.amazeawn.common.messaging;

import org.slf4j.MDC;

import java.util.UUID;

public final class CorrelationId {
    private CorrelationId() {}

    public static final String MDC_KEY = "correlationId";

    public static String current() {
        String id = MDC.get(MDC_KEY);
        if (id == null) {
            id = UUID.randomUUID().toString();
            MDC.put(MDC_KEY, id);
        }
        return id;
    }

    public static void set(String id) {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, id);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
