package com.github.devnic0las.ledgerlens.ledger.domain;

import java.util.Map;
import java.util.UUID;

public record Entry(UUID id, UUID accountId, Direction direction, long amountCents) {

  public Entry {
    if(amountCents <= 0) {
      throw new IllegalArgumentException("Amount must be positive");
    }
  }

}
