package com.github.devnic0las.ledgerlens.ledger.domain;

import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.IdempotencyConflictException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Modelo em memória do ledger, usado para derivar o saldo a partir dos lançamentos sem
 * banco. Não é o caminho de persistência: quem grava é {@link LedgerRepository}.
 *
 * <p>Thread-safe por composição: três operações mexem em estado compartilhado
 * ({@code register}, {@code post}, {@code balanceOf}) e todas são {@code synchronized}
 * no mesmo monitor. O check-then-act da idempotência fica coberto porque checar a chave,
 * validar as contas e gravar acontecem dentro de uma única seção crítica — nenhuma thread
 * observa o estado intermediário.
 *
 * <p>Sincronizar apenas o {@code post} não bastaria: {@code balanceOf} percorre a mesma
 * lista de transações, e ver a lista no meio de um {@code add} produziria saldo errado.
 */
public class Ledger {

  /** Ordem de inserção preservada: a leitura reflete a ordem em que os lançamentos chegaram. */
  private final Map<UUID, Account> accounts = new LinkedHashMap<>();
  private final List<Transaction> transactions = new java.util.ArrayList<>();
  private final Map<String, Transaction> postedByKey = new LinkedHashMap<>();

  public synchronized void register(Account account) {
    accounts.put(account.id(), account);
  }

  public synchronized PostResult post(Transaction transaction) {
    Transaction posted = postedByKey.get(transaction.idempotencyKey());

    if (posted != null) {
      if (sameContent(posted, transaction)) {
        return PostResult.DUPLICATE;
      }
      throw new IdempotencyConflictException(
              "Idempotency key reused with different content: " + transaction.idempotencyKey());
    }

    for (Entry entry : transaction.entries()) {
      if (!accounts.containsKey(entry.accountId())) {
        throw new IllegalArgumentException("Account not found: " + entry.accountId());
      }
    }

    transactions.add(transaction);
    postedByKey.put(transaction.idempotencyKey(), transaction);

    return PostResult.POSTED;
  }

  public synchronized long balanceOf(UUID accountId) {
    Account account = accounts.get(accountId);
    if (account == null) {
      throw new IllegalArgumentException("Account not found");
    }

    long balance = 0;

    for (Transaction transaction : transactions) {
      for (Entry entry : transaction.entries()) {
        if (entry.accountId().equals(accountId)) {
          if (entry.direction() == account.type().normalSide()) {
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
