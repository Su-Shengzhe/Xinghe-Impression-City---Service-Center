package com.xinghe.trade.messaging;

import java.time.LocalDateTime;

public record OutboxEvent(String eventId, String eventType, String routingKey, String payload,
                          LocalDateTime availableAt, int attempts) {}