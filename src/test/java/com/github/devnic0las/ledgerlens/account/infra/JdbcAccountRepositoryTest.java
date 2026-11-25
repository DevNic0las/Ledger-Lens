package com.github.devnic0las.ledgerlens.account.infra;

import com.github.devnic0las.ledgerlens.TestcontainersConfiguration;
import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountRepository;
import com.github.devnic0las.ledgerlens.account.domain.AccountType;
import com.github.devnic0las.ledgerlens.account.domain.exception.AccountAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcAccountRepositoryTest {

  @Autowired
  JdbcClient jdbc;

  @Autowired
  AccountRepository repository;

  @BeforeEach
  void setUp() {
    jdbc.sql("TRUNCATE TABLE entry, transaction, account").update();
  }

  @Test
  void savesAndReadsAccountById() {
    var account = new Account(UUID.randomUUID(), "Conta Corrente", "BRL", AccountType.ASSET);

    repository.save(account);

    var found = repository.findAllByIds(Set.of(account.id()));
    assertEquals(Map.of(account.id(), account), found);
  }

  /** Uma chave por id, só com as contas pedidas: nenhuma linha de fora vaza para o mapa. */
  @Test
  void findsOnlyTheRequestedAccountsKeyedById() {
    var checking = anAccount("Conta Corrente", "BRL", AccountType.ASSET);
    var revenue = anAccount("Conta Receita", "BRL", AccountType.REVENUE);
    anAccount("Conta Não Pedida", "BRL", AccountType.EXPENSE);

    var found = repository.findAllByIds(Set.of(checking.id(), revenue.id()));

    assertEquals(2, found.size());
    assertEquals(checking, found.get(checking.id()));
    assertEquals(revenue, found.get(revenue.id()));
  }

  /** Ids pedidos que não existem simplesmente faltam no mapa; é o serviço quem decide que faltam. */
  @Test
  void omitsIdsThatDoNotExist() {
    var checking = anAccount("Conta Corrente", "BRL", AccountType.ASSET);
    var unknown = UUID.randomUUID();

    var found = repository.findAllByIds(Set.of(checking.id(), unknown));

    assertEquals(Set.of(checking.id()), found.keySet());
  }

  /** {@code WHERE id IN ()} é SQL inválido no Postgres: conjunto vazio não pode chegar ao banco. */
  @Test
  void emptyIdSetReturnsEmptyMap() {
    anAccount("Conta Corrente", "BRL", AccountType.ASSET);

    assertTrue(repository.findAllByIds(Set.of()).isEmpty());
  }

  @Test
  void sameNameAndCurrencyIsRejectedAsDomainException() {
    anAccount("Conta Corrente", "BRL", AccountType.ASSET);

    var duplicate = new Account(UUID.randomUUID(), "Conta Corrente", "BRL", AccountType.LIABILITY);

    assertThrows(AccountAlreadyExistsException.class, () -> repository.save(duplicate));
    assertEquals(1, rowCount());
  }

  /** O `ON CONFLICT` nomeia `(name, currency)`: o conflito vem do banco, não de uma pré-consulta. */
  @Test
  void duplicateIsDecidedByTheConstraintNotByAPreviousSelect() {
    var original = anAccount("Caixa", "BRL", AccountType.ASSET);

    var duplicate = new Account(UUID.randomUUID(), "Caixa", "BRL", AccountType.EXPENSE);

    var exception = assertThrows(AccountAlreadyExistsException.class, () -> repository.save(duplicate));

    assertTrue(exception.getMessage().contains("Caixa"));
    assertTrue(exception.getMessage().contains("BRL"));
    assertEquals(1, rowCount());
    assertEquals(original, repository.findAllByIds(Set.of(original.id())).get(original.id()));
  }

  /**
   * Colisão de PK não é "conta já existe": {@code (name, currency)} é a regra de negócio,
   * {@code id} é infraestrutura. Reusar um id com outro nome sobe
   * {@code DuplicateKeyException} (→ 500), e não {@link AccountAlreadyExistsException} (→ 409),
   * porque esconder isso como conflito de negócio mascararia um bug de quem gera id.
   */
  @Test
  void primaryKeyCollisionIsNotReportedAsAlreadyExists() {
    var original = anAccount("Conta Corrente", "BRL", AccountType.ASSET);

    var sameIdOtherName = new Account(original.id(), "Outro Nome", "EUR", AccountType.LIABILITY);

    assertThrows(DuplicateKeyException.class, () -> repository.save(sameIdOtherName));
  }

  @Test
  void sameNameInAnotherCurrencyIsAllowed() {
    var inBrl = anAccount("Caixa", "BRL", AccountType.ASSET);
    var inUsd = anAccount("Caixa", "USD", AccountType.ASSET);

    var found = repository.findAllByIds(Set.of(inBrl.id(), inUsd.id()));

    assertEquals(2, found.size());
  }

  private Account anAccount(String name, String currency, AccountType type) {
    var account = new Account(UUID.randomUUID(), name, currency, type);
    repository.save(account);
    return account;
  }

  private long rowCount() {
    return jdbc.sql("SELECT count(*) FROM account").query(Long.class).single();
  }
}
