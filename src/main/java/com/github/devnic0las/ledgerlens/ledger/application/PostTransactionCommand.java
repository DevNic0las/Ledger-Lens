package com.github.devnic0las.ledgerlens.ledger.application;

import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pedido de lançamento. O cliente informa apenas contas, direções e valores: {@code id} da
 * transação, {@code id} de cada lançamento e {@code occurredAt} são gerados pelo servidor,
 * então nada vindo de fora pode colidir com uma PK existente.
 *
 * <p>A validação aqui é de forma (campo obrigatório, lista não vazia). A validação contábil
 * (mínimo de dois lançamentos, débitos = créditos) é do construtor de {@link Transaction}.
 */
public record PostTransactionCommand(String description, String idempotencyKey,
                                     List<RequestedEntry> entries) {

  public PostTransactionCommand {
    if (description == null) {
      throw new NullPointerException("Transaction description is required");
    }
    if (description.isBlank()) {
      throw new IllegalArgumentException("Transaction description must not be blank");
    }
    if (idempotencyKey == null) {
      throw new NullPointerException("Idempotency-Key is required");
    }
    if (idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("Idempotency-Key must not be blank");
    }
    if (entries == null) {
      throw new NullPointerException("Transaction entries are required");
    }
    entries = List.copyOf(entries);
  }

  /** Materializa o agregado do domínio, atribuindo as identidades que são do servidor. */
  public Transaction toTransaction(UUID id, Instant occurredAt) {
    return new Transaction(id, occurredAt, description, idempotencyKey, entries.stream()
            .map(entry -> new Entry(UUID.randomUUID(), entry.accountId(), entry.direction(), entry.amountCents()))
            .toList());
  }

  public record RequestedEntry(UUID accountId, Direction direction, long amountCents) {

    public RequestedEntry {
      if (accountId == null) {
        throw new NullPointerException("Entry accountId is required");
      }
      if (direction == null) {
        throw new NullPointerException("Entry direction is required");
      }
    }
  }
}
