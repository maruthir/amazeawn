package com.amazeawn.common.messaging;

public final class Routing {
    private Routing() {}

    public static final String EXCHANGE = "amazeawn";
    public static final String DLX = "amazeawn.dlx";

    /** Commands flow API -> processor. */
    public static final String PROCESSOR_QUEUE = "processor.commands";
    public static final String PROCESSOR_DLQ = "processor.commands.dlq";

    /** Events flow processor -> API. */
    public static final String API_QUEUE = "api.events";
    public static final String API_DLQ = "api.events.dlq";

    public static final String ORDER_PLACE = "order.place";
    public static final String ORDER_CANCEL = "order.cancel";
    public static final String REFUND_EXECUTE = "refund.execute";
    public static final String PRODUCT_UPSERT = "product.upsert";
    public static final String INVENTORY_ADJUST = "inventory.adjust";
    public static final String INVENTORY_RESTOCK = "inventory.restock";
    public static final String TEST_RESET = "test.reset";

    public static final String ORDER_CONFIRMED = "order.confirmed";
    public static final String ORDER_REJECTED = "order.rejected";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String REFUND_COMPLETED = "refund.completed";
    public static final String INVENTORY_CHANGED = "inventory.changed";

    public static final String[] COMMAND_KEYS = {
            ORDER_PLACE, ORDER_CANCEL, REFUND_EXECUTE, PRODUCT_UPSERT,
            INVENTORY_ADJUST, INVENTORY_RESTOCK, TEST_RESET
    };
    public static final String[] EVENT_KEYS = {
            ORDER_CONFIRMED, ORDER_REJECTED, ORDER_CANCELLED, REFUND_COMPLETED, INVENTORY_CHANGED
    };

    public static final String HEADER_CORRELATION_ID = "x-correlation-id";
    public static final String HEADER_ATTEMPT = "x-attempt";
}
