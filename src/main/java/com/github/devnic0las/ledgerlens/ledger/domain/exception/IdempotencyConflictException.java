package com.github.devnic0las.ledgerlens.ledger.domain.exception;

public class IdempotencyConflictException extends RuntimeException {

  public IdempotencyConflictException(String message) {
    super(message);
  }
}
