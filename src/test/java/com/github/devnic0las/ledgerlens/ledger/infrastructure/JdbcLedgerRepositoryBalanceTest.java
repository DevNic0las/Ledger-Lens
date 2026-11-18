package com.github.devnic0las.ledgerlens.ledger.infrastructure;

import com.github.devnic0las.ledgerlens.TestcontainersConfiguration;
import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountType;
import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.Ledger;
import com.github.devnic0las.ledgerlens.ledger.domain.LedgerRepository;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcLedgerRepositoryBalanceTest {

  @Autowired
  JdbcClient jdbc;

  @Autowired
  LedgerRepository repository;

  @BeforeEach
  void setUp() {
    jdbc.sql("TRUNCATE TABLE entry, transaction, account").update();
  }

  /**
   * Um cenário por tipo de conta, com o esperado escrito à mão (não calculado no teste).
   * Cada cenário lança débito e crédito na mesma conta, para os dois lados do tipo serem
   * exercitados. O sinal segue o lado normal: ASSET/EXPENSE somam débito,
   * LIABILITY/EQUITY/REVENUE somam crédito.
   */
  @ParameterizedTest(name = "{0}: débito {1} e crédito {2} => {3}")
  @CsvSource({
          "ASSET, 10000, 3000, 7000",
          "EXPENSE, 5000, 2000, 3000",
          "LIABILITY, 2000, 8000, 6000",
          "EQUITY, 1000, 4000, 3000",
          "REVENUE, 10000, 10000, 0"
  })
  void balanceFollowsNormalSideOfEachAccountType(String type, long debitCents, long creditCents, long expectedCents) {
    var target = anAccount("Conta do cenário", type);
    var counterpart = anAccount("Contrapartida", "ASSET");

    post(target, Direction.DEBIT, debitCents, counterpart);
    post(target, Direction.CREDIT, creditCents, counterpart);

    assertEquals(expectedCents, repository.balanceOf(target));
  }

  @Test
  void balanceCanBeNegative() {
    var checking = anAccount("Conta Corrente", "ASSET");
    var counterpart = anAccount("Contrapartida", "ASSET");

    post(checking, Direction.DEBIT, 2_000, counterpart);
    post(checking, Direction.CREDIT, 5_000, counterpart);

    assertEquals(-3_000, repository.balanceOf(checking));
  }

  @Test
  void accountWithoutEntriesHasZeroBalance() {
    var untouched = anAccount("Conta sem lançamentos", "REVENUE");

    assertEquals(0, repository.balanceOf(untouched));
  }

  @Test
  void unknownAccountThrows() {
    assertThrows(IllegalArgumentException.class, () -> repository.balanceOf(UUID.randomUUID()));
  }

  /**
   * O SQL e o Ledger em memória concordam para as mesmas contas e transações. Este teste
   * é o que protege a duplicação da regra de lado normal que existe entre
   * AccountType.normalSide() e o CASE da query.
   *
   * <p>A conta é criada a partir de {@link AccountType#values()}, e não de uma lista fixa
   * de tipos: um tipo novo no enum entra no cenário automaticamente. Cada conta recebe
   * débito e crédito, então as duas direções são exercitadas em todos os tipos. Se um tipo
   * novo não tiver ramo no CASE do SQL, a soma dele cai no NULL do CASE e a comparação
   * com o Ledger em memória falha — é assim que o tipo órfão aparece, sem que o SQL
   * precise validar nada.
   */
  @Test
  void sqlBalanceMatchesInMemoryLedgerForEveryAccountType() {
    var ledger = new Ledger();
    var accounts = new ArrayList<Account>();

    // Contrapartida neutra: só existe para equilibrar as transações.
    var counterpart = anAccountRegisteredIn(ledger, "Contrapartida", AccountType.ASSET);

    int cents = 1_000;
    for (AccountType type : AccountType.values()) {
      var account = anAccountRegisteredIn(ledger, "Conta " + type, type);
      accounts.add(account);

      // Débito e crédito na mesma conta, valores diferentes, para os dois lados do tipo.
      cents += 1_000;
      pairAndPost(ledger, account, Direction.DEBIT, cents, counterpart);
      cents += 1_000;
      pairAndPost(ledger, account, Direction.CREDIT, cents, counterpart);
    }

    for (Account account : accounts) {
      assertEquals(
              ledger.balanceOf(account.id()),
              repository.balanceOf(account.id()),
              "saldo divergiu na conta " + account.name() + " (" + account.type() + ")");
    }
  }

  private void pairAndPost(Ledger ledger, Account account, Direction direction, long cents, Account counterpart) {
    var opposite = direction == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT;
    var transaction = pair(account, direction, cents, opposite, counterpart);
    repository.save(transaction);
    ledger.post(transaction);
  }

  private UUID anAccount(String name, String type) {
    var id = UUID.randomUUID();
    jdbc.sql("INSERT INTO account (id, name, type, currency) VALUES (:id, :name, :type, :currency)")
            .param("id", id)
            .param("name", name)
            .param("type", type)
            .param("currency", "BRL")
            .update();
    return id;
  }

  private Account anAccountRegisteredIn(Ledger ledger, String name, String type) {
    var id = anAccount(name, type);
    var account = new Account(id, name, "BRL", AccountType.valueOf(type));
    ledger.register(account);
    return account;
  }

  private Account anAccountRegisteredIn(Ledger ledger, String name, AccountType type) {
    return anAccountRegisteredIn(ledger, name, type.name());
  }

  /** Lança a conta alvo no lado pedido e equilibra a transação na contrapartida. */
  private void post(UUID accountId, Direction direction, long cents, UUID counterpartId) {
    var opposite = direction == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT;
    repository.save(new Transaction(
            UUID.randomUUID(),
            nowMicros(),
            "Ajuste",
            UUID.randomUUID().toString(),
            List.of(
                    new Entry(UUID.randomUUID(), accountId, direction, cents),
                    new Entry(UUID.randomUUID(), counterpartId, opposite, cents))));
  }

  private Transaction pair(Account first, Direction firstSide, long cents,
                           Direction secondSide, Account second) {
    return new Transaction(
            UUID.randomUUID(),
            nowMicros(),
            "Lançamento",
            UUID.randomUUID().toString(),
            List.of(
                    new Entry(UUID.randomUUID(), first.id(), firstSide, cents),
                    new Entry(UUID.randomUUID(), second.id(), secondSide, cents)));
  }

  /** TIMESTAMPTZ guarda microssegundos; gerar o instante já nessa precisão deixa o round-trip exato. */
  private static Instant nowMicros() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
}
