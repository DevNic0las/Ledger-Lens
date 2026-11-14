package com.github.devnic0las.ledgerlens.account.domain;

import com.github.devnic0las.ledgerlens.ledger.domain.Direction;


public enum AccountType {
  ASSET(Direction.DEBIT),
  EXPENSE(Direction.DEBIT),
  LIABILITY(Direction.CREDIT),
  EQUITY(Direction.CREDIT),
  REVENUE(Direction.CREDIT);

  private final Direction normalSide;

  AccountType(Direction normalSide) {
    this.normalSide = normalSide;
  }

  public Direction normalSide() {
    return normalSide;
  }
}