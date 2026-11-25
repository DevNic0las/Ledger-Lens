package com.github.devnic0las.ledgerlens.ledger.api;

import com.github.devnic0las.ledgerlens.ledger.domain.Entry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Corpo de resposta de {@code POST /transactions}, idêntico para 201 e 200. */
record TransactionResponse(UUID id, Instant occurredAt, String description, List<EntryResponse> entries) {

  static TransactionResponse of(com.github.devnic0las.ledgerlens.ledger.domain.Transaction transaction) {
    return new TransactionResponse(
            transaction.id(),
            transaction.occurredAt(),
            transaction.description(),
            transaction.entries().stream().map(EntryResponse::of).toList());
  }

  record EntryResponse(UUID id, UUID accountId, String direction, long amountCents) {

    static EntryResponse of(Entry entry) {
      return new EntryResponse(entry.id(), entry.accountId(), entry.direction().name(), entry.amountCents());
    }
  }
}
