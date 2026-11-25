package com.github.devnic0las.ledgerlens.ledger.domain;

import com.github.devnic0las.ledgerlens.ledger.domain.exception.InvalidEntryException;

import java.util.UUID;

public record Entry(UUID id, UUID accountId, Direction direction, long amountCents) {

  public Entry {
    if(amountCents <= 0) {
      throw new InvalidEntryException("Amount must be positive");
    }
  }

}
