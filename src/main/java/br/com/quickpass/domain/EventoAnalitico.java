package br.com.quickpass.domain;

import java.time.Instant;
import java.util.Map;

public record EventoAnalitico(String eventName, Instant timestamp, String userId, String papel,
                              String sessionId, Map<String, Object> props) {
}
