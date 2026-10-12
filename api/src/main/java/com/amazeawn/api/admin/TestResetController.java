package com.amazeawn.api.admin;

import com.amazeawn.api.catalog.CatalogSeeder;
import com.amazeawn.api.config.AppProperties;
import com.amazeawn.common.messaging.Commands;
import com.amazeawn.common.messaging.CorrelationId;
import com.amazeawn.common.outbox.Outbox;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Only active with the {@code loadtest} profile. Wipes orders, carts, refunds and (optionally)
 * customers, then reseeds the catalog and asks the processor to reset inventory to the given level.
 */
@RestController
@RequestMapping("/api/v1/test")
@Profile("loadtest")
@Tag(name = "Test (loadtest profile only)")
public class TestResetController {

    private final EntityManager em;
    private final CatalogSeeder seeder;
    private final Outbox outbox;
    private final AppProperties props;

    public TestResetController(EntityManager em, CatalogSeeder seeder, Outbox outbox, AppProperties props) {
        this.em = em;
        this.seeder = seeder;
        this.outbox = outbox;
        this.props = props;
    }

    @PostMapping("/reset")
    @Transactional
    @Operation(summary = "Wipe orders and reseed between load runs (no auth; profile-gated)")
    public Map<String, Object> reset(@RequestParam(required = false) Integer catalogSize,
                                     @RequestParam(required = false) Integer stock,
                                     @RequestParam(defaultValue = "false") boolean dropCustomers) {
        int size = catalogSize != null ? catalogSize : props.seed().catalogSize();
        int level = stock != null ? stock : props.seed().initialStock();
        Instant issuedAt = Instant.now(); // everything the processor created before this is stale

        // Native SQL does not pick up Hibernate's default_schema; {h-schema} expands to "api.".
        em.createNativeQuery("truncate table {h-schema}refunds, {h-schema}order_items, {h-schema}orders, {h-schema}idempotency_keys, "
                + "{h-schema}cart_items, {h-schema}carts, {h-schema}processed_messages").executeUpdate();
        em.createNativeQuery("delete from {h-schema}outbox where published_at is null").executeUpdate();
        if (dropCustomers) {
            em.createNativeQuery("delete from {h-schema}refresh_tokens where user_id in (select id from {h-schema}users where role = 'CUSTOMER')").executeUpdate();
            em.createNativeQuery("delete from {h-schema}addresses where user_id in (select id from {h-schema}users where role = 'CUSTOMER')").executeUpdate();
            em.createNativeQuery("delete from {h-schema}users where role = 'CUSTOMER'").executeUpdate();
        }
        em.createNativeQuery("delete from {h-schema}products").executeUpdate();
        em.flush();
        em.clear();

        seeder.seed(size, level);
        outbox.enqueue(new Commands.TestReset(UUID.randomUUID(), CorrelationId.current(), level, issuedAt));
        return Map.of("status", "reset", "catalogSize", size, "stock", level, "dropCustomers", dropCustomers);
    }
}
