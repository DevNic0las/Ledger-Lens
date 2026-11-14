package com.github.devnic0las.ledgerlens.ledger.domain;

import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountType;

import java.util.*;

public class Ledger {

  private final Map<UUID, Account> accounts = new HashMap<>();
  private final List<Transaction> transactions = new ArrayList<>();
  private final Set<String> usedKeys = new HashSet<>();

  public void register(Account account) {

    accounts.put(account.id(), account);

  }
  public void post(Transaction transaction) {

    if(usedKeys.contains(transaction.idempotencyKey())) {
      throw new IllegalArgumentException("Transaction with this idempotency key has already been posted");
    }

    transaction.entries().forEach(entry -> {
      if(!accounts.containsKey(entry.accountId())) {
        throw new IllegalArgumentException("Account not found: " + entry.accountId());
      }
    });

    transactions.add(transaction);
    usedKeys.add(transaction.idempotencyKey());

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
}
