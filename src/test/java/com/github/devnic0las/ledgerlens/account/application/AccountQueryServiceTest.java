package com.github.devnic0las.ledgerlens.account.application;

import com.github.devnic0las.ledgerlens.TestcontainersConfiguration;
import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountRepository;
import com.github.devnic0las.ledgerlens.account.domain.AccountType;
import com.github.devnic0las.ledgerlens.account.domain.exception.AccountNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Leitura de contas pela camada de aplicação. A regra de "não existe" é a mesma da leitura de
 * saldo: ausência explícita vira {@link AccountNotFoundException}, nunca uma falha genérica.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountQueryServiceTest {

  @Autowired
  JdbcClient jdbc;

  @Autowired
  AccountQueryService service;

  @Autowired
  AccountRepository accountRepository;

  @BeforeEach
  void setUp() {
    jdbc.sql("TRUNCATE TABLE entry, transaction, account").update();
  }

  @Test
  void findsAnExistingAccount() {
    var account = anAccount("Conta Corrente", "BRL", AccountType.ASSET);

    assertEquals(account, service.findById(account.id()));
  }

  @Test
  void unknownAccountThrowsNotFound() {
    var unknown = UUID.randomUUID();

    var exception = assertThrows(AccountNotFoundException.class, () -> service.findById(unknown));

    assertEquals(unknown, exception.accountId());
  }

  @Test
  void listsEveryAccount() {
    var checking = anAccount("Conta Corrente", "BRL", AccountType.ASSET);
    var revenue = anAccount("Conta Receita", "USD", AccountType.REVENUE);

    var all = service.findAll();

    assertEquals(2, all.size());
    assertTrue(all.containsAll(java.util.List.of(checking, revenue)));
  }

  @Test
  void returnsEmptyListWhenThereIsNoAccount() {
    assertTrue(service.findAll().isEmpty());
  }

  private Account anAccount(String name, String currency, AccountType type) {
    var account = new Account(UUID.randomUUID(), name, currency, type);
    accountRepository.save(account);
    return account;
  }
}
