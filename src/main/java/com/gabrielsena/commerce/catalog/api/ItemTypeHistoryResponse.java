package com.gabrielsena.commerce.catalog.api;

import com.gabrielsena.commerce.catalog.domain.ItemTypeHistory;
import com.gabrielsena.commerce.catalog.domain.SizeFormat;

import java.time.Instant;
import java.util.UUID;

public record ItemTypeHistoryResponse(UUID id, UUID actorId, Instant occurredAt, ItemTypeHistory.Action action,
                                      Values before, Values after) {
    public record Values(String name, Boolean hasColor, Boolean hasSize, SizeFormat sizeFormat) {
    }

    public static ItemTypeHistoryResponse from(ItemTypeHistory history) {
        Values before = history.getOldName() == null ? null : new Values(history.getOldName(),
                history.getOldHasColor(), history.getOldHasSize(), history.getOldSizeFormat());
        Values after = history.getNewName() == null ? null : new Values(history.getNewName(),
                history.getNewHasColor(), history.getNewHasSize(), history.getNewSizeFormat());
        return new ItemTypeHistoryResponse(history.getId(), history.getActorId(), history.getOccurredAt(),
                history.getAction(), before, after);
    }
}
