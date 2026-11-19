package com.github.devnic0las.ledgerlens.ledger.domain;

import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.IdempotencyConflictException;

import java.util.*;

public class Ledger {

  private final Map<UUID, Account> accounts = new HashMap<>();
  private final List<Transaction> transactions = new ArrayList<>();
  private final Map<String, Transaction> postedByKey = new HashMap<>();

  public void register(Account account) {

    accounts.put(account.id(), account);

  }
  public PostResult post(Transaction transaction) {

  Transaction posted = postedByKey.get(transaction.idempotencyKey());

    if (posted != null) {
      if (sameContent(posted, transaction)) {
        return PostResult.DUPLICATE;
      }
      throw new IdempotencyConflictException(
              "Idempotency key reused with different content: " + transaction.idempotencyKey());
    }

    transaction.entries().forEach(entry -> {
      if(!accounts.containsKey(entry.accountId())) {
        throw new IllegalArgumentException("Account not found: " + entry.accountId());
      }
    });

    transactions.add(transaction);
    postedByKey.put(transaction.idempotencyKey(), transaction);

    return PostResult.POSTED;
  }

  public long balanceOf(UUID accountId) {

    Account account = accounts.get(accountId);
    if (account == null) {
      throw new IllegalArgumentException("Account not found");
    }

    long balance = 0;

    for (Transaction transaction : transactions) {
      for (Entry entry : transaction.entries()) {
        if (entry.accountId().equals(accountId)) {
          if(entry.direction() == account.type().normalSide()) {
            balance += entry.amountCents();
          } else {
            balance -= entry.amountCents();
          }
        }
      }
    }
    return balance;

  }
  private boolean sameContent(Transaction a, Transaction b) {
    return IdempotencyContent.matches(a, b);
  }
}
