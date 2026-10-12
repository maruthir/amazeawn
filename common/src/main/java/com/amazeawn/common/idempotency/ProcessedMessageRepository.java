package com.amazeawn.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, UUID> {

    /** Returns 1 when this is the first time the message is seen, 0 when it is a duplicate. */
    @Modifying
    @Query(value = "insert into {h-schema}processed_messages (message_id, processed_at) values (:id, now()) on conflict do nothing",
            nativeQuery = true)
    int insertIfAbsent(UUID id);
}
