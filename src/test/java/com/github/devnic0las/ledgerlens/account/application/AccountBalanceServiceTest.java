package com.github.devnic0las.ledgerlens.account.application;

import com.github.devnic0las.ledgerlens.TestcontainersConfiguration;
import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountRepository;
import com.github.devnic0las.ledgerlens.account.domain.AccountType;
import com.github.devnic0las.ledgerlens.account.domain.exception.AccountNotFoundException;
import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.LedgerRepository;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Leitura de saldo na camada de aplicação. O adaptador JDBC já sinaliza conta inexistente com
 * {@code IllegalArgumentException}, ambíguo demais para a borda: o serviço traduz para
 * {@link AccountNotFoundException}, que vira 404 sem depender de como o SQL falhou.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountBalanceServiceTest {

  @Autowired
  JdbcClient jdbc;

  @Autowired
  AccountBalanceService service;

  @Autowired
  AccountRepository accountRepository;

  @Autowired
  LedgerRepository ledgerRepository;

  private Account checking;
  private Account revenue;

  @BeforeEach
  void setUp() {
    jdbc.sql("TRUNCATE TABLE entry, transaction, account").update();
    checking = anAccount("Conta Corrente", AccountType.ASSET);
    revenue = anAccount("Conta Receita", AccountType.REVENUE);
  }

  @Test
  void returnsBalanceDerivedFromEntries() {
    post(10_000);

    assertEquals(10_000, service.balanceOf(checking.id()));
  }

  @Test
  void accountWithoutEntriesHasZeroBalance() {
    assertEquals(0, service.balanceOf(checking.id()));
  }

  @Test
  void unknownAccountThrowsAccountNotFound() {
    var unknown = UUID.randomUUID();

    var exception = assertThrows(AccountNotFoundException.class, () -> service.balanceOf(unknown));

    assertEquals(unknown, exception.accountId());
  }

  private void post(long cents) {
    ledgerRepository.save(new Transaction(UUID.randomUUID(), Instant.now(), "Pix", "chave-" + cents,
            List.of(
                    new Entry(UUID.randomUUID(), checking.id(), Direction.DEBIT, cents),
                    new Entry(UUID.randomUUID(), revenue.id(), Direction.CREDIT, cents))));
  }

  private Account anAccount(String name, AccountType type) {
    var account = new Account(UUID.randomUUID(), name, "BRL", type);
    accountRepository.save(account);
    return account;
  }
}
