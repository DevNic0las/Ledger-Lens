package com.github.devnic0las.ledgerlens.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Transaction(UUID id, Instant occurredAt, String description,
                          String idempotencyKey, List<Entry> entries) {


  public Transaction {
    if (id == null) {
      throw new NullPointerException("Transaction id is required");
    }
    if(entries.size() < 2 ) {
      throw new IllegalArgumentException("Transaction must have at least two entries");
    }
    entries = List.copyOf(entries);



    long debits = 0;
    long credits = 0;

    for (Entry entry : entries) {
      if (entry.direction() == Direction.DEBIT) {
        debits += entry.amountCents();
      } else {
        credits += entry.amountCents();
      }
    }

    if (debits != credits) {
      throw new IllegalArgumentException("Transaction is unbalanced");
    }
  }
}
