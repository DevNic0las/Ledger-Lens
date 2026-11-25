package com.github.devnic0las.ledgerlens.ledger.application;

import com.github.devnic0las.ledgerlens.TestcontainersConfiguration;
import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountRepository;
import com.github.devnic0las.ledgerlens.account.domain.AccountType;
import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.LedgerRepository;
import com.github.devnic0las.ledgerlens.ledger.domain.PostResult;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.AccountsNotFoundException;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.IdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Caso de uso de lançamento contra Postgres real: validação de contas, idempotência e
 * atomicidade, tudo do ponto de vista de quem chama o serviço (e não do repositório).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostTransactionServiceTest {

  @Autowired
  JdbcClient jdbc;

  @Autowired
  PostTransactionService service;

  @Autowired
  LedgerRepository ledgerRepository;

  @Autowired
  AccountRepository accountRepository;

  @Autowired
  PlatformTransactionManager transactionManager;

  private Account checking;
  private Account revenue;

  @BeforeEach
  void setUp() {
    jdbc.sql("TRUNCATE TABLE entry, transaction, account").update();
    checking = anAccount("Conta Corrente", AccountType.ASSET);
    revenue = anAccount("Conta Receita", AccountType.REVENUE);
  }

  @Test
  void postsBalancedTransactionAndGeneratesItsIdentity() {
    var before = Instant.now();

    var outcome = service.post(pix("chave-1", 10_000));

    assertEquals(PostResult.POSTED, outcome.result());
    assertNotNull(outcome.transaction().id());
    assertFalse(outcome.transaction().occurredAt().isBefore(before.minusSeconds(1)));
    assertEquals(2, outcome.transaction().entries().size());

    var stored = ledgerRepository.findById(outcome.transaction().id()).orElseThrow();
    assertEquals(outcome.transaction().id(), stored.id());
  }

  /** Retry da mesma chave com o mesmo conteúdo devolve a transação original, sem duplicar. */
  @Test
  void duplicateRequestReturnsTheOriginalTransaction() {
    var first = service.post(pix("chave-1", 10_000));

    var second = service.post(pix("chave-1", 10_000));

    assertEquals(PostResult.DUPLICATE, second.result());
    assertEquals(first.transaction().id(), second.transaction().id());
    assertEquals(1, rowCount("transaction"));
    assertEquals(2, rowCount("entry"));
  }

  @Test
  void sameKeyWithDifferentContentThrowsAndKeepsTheOriginal() {
    var first = service.post(pix("chave-1", 10_000));

    assertThrows(IdempotencyConflictException.class, () -> service.post(pix("chave-1", 99_999)));

    assertEquals(1, rowCount("transaction"));
    assertEquals(2, rowCount("entry"));
    var stored = ledgerRepository.findByIdempotencyKey("chave-1").orElseThrow();
    assertEquals(first.transaction().id(), stored.id());
  }

  /**
   * Mesma chave, mesmos lançamentos, descrição diferente: o cliente reenviou outro pedido com
   * uma chave que deveria ser exclusiva. Devolver 200 com a original mentiria para ele.
   */
  @Test
  void sameKeyWithDifferentDescriptionThrowsAndKeepsTheOriginal() {
    var first = service.post(new PostTransactionCommand("Pix aluguel", "chave-1", List.of(
            new PostTransactionCommand.RequestedEntry(checking.id(), Direction.DEBIT, 10_000),
            new PostTransactionCommand.RequestedEntry(revenue.id(), Direction.CREDIT, 10_000))));

    assertThrows(IdempotencyConflictException.class, () ->
            service.post(new PostTransactionCommand("Pix mercado", "chave-1", List.of(
                    new PostTransactionCommand.RequestedEntry(checking.id(), Direction.DEBIT, 10_000),
                    new PostTransactionCommand.RequestedEntry(revenue.id(), Direction.CREDIT, 10_000)))));

    assertEquals(1, rowCount("transaction"));
    var stored = ledgerRepository.findByIdempotencyKey("chave-1").orElseThrow();
    assertEquals(first.transaction().id(), stored.id());
    assertEquals("Pix aluguel", stored.description());
  }

  /**
   * O serviço valida as contas antes do {@code save}: sem isso quem estoura é a FK, como
   * {@code DataIntegrityViolationException}, e a API não teria como listar quais ids faltam.
   */
  @Test
  void missingAccountsAreReportedBeforeAnyWrite() {
    var missingA = UUID.randomUUID();
    var missingB = UUID.randomUUID();

    var error = assertThrows(AccountsNotFoundException.class, () -> service.post(
            new PostTransactionCommand("Pix parcial", "chave-1", List.of(
                    new PostTransactionCommand.RequestedEntry(checking.id(), Direction.DEBIT, 500),
                    new PostTransactionCommand.RequestedEntry(missingA, Direction.CREDIT, 500),
                    new PostTransactionCommand.RequestedEntry(missingB, Direction.DEBIT, 100),
                    new PostTransactionCommand.RequestedEntry(revenue.id(), Direction.CREDIT, 100)))));

    assertEquals(Set.of(missingA, missingB), error.missingAccountIds(), "só os ids que faltam, nem mais nem menos");
    assertEquals(0, rowCount("transaction"));
    assertEquals(0, rowCount("entry"));
  }

  /** Falhar na validação de conta não pode consumir a chave de idempotência. */
  @Test
  void failedAccountValidationDoesNotBurnTheIdempotencyKey() {
    var command = pix("chave-1", 10_000);
    var withMissingAccount = new PostTransactionCommand(command.description(), command.idempotencyKey(), List.of(
            new PostTransactionCommand.RequestedEntry(checking.id(), Direction.DEBIT, 10_000),
            new PostTransactionCommand.RequestedEntry(UUID.randomUUID(), Direction.CREDIT, 10_000)));

    assertThrows(AccountsNotFoundException.class, () -> service.post(withMissingAccount));

    assertEquals(PostResult.POSTED, service.post(command).result());
    assertEquals(1, rowCount("transaction"));
    assertEquals(2, rowCount("entry"));
  }

  /** A mesma conta em mais de um lançamento é validada uma vez e não gera falso "faltante". */
  @Test
  void validatesAnAccountUsedBySeveralEntriesOnlyOnce() {
    var outcome = service.post(new PostTransactionCommand("Pix em duas pernas", "chave-1", List.of(
            new PostTransactionCommand.RequestedEntry(checking.id(), Direction.DEBIT, 300),
            new PostTransactionCommand.RequestedEntry(checking.id(), Direction.DEBIT, 700),
            new PostTransactionCommand.RequestedEntry(revenue.id(), Direction.CREDIT, 1_000))));

    assertEquals(PostResult.POSTED, outcome.result());
    assertEquals(3, rowCount("entry"));
  }

  /**
   * Precedência de erro: uma transação desbalanceada é inválida por si só (400), então o
   * construtor do domínio fala primeiro e a conta faltante só seria avaliada depois.
   */
  @Test
  void unbalancedTransactionIsRejectedBeforeAccountValidation() {
    var command = new PostTransactionCommand("Pix torto", "chave-1", List.of(
            new PostTransactionCommand.RequestedEntry(checking.id(), Direction.DEBIT, 500),
            new PostTransactionCommand.RequestedEntry(UUID.randomUUID(), Direction.CREDIT, 400)));

    assertThrows(IllegalArgumentException.class, () -> service.post(command));
    assertEquals(0, rowCount("transaction"));
  }

  /**
   * O conflito de idempotência precisa propagar: dentro de {@code @Transactional} isso marca a
   * transação como rollback-only, e o efeito colateral de quem chamou não commita.
   */
  @Test
  void conflictPropagatesSoTheCallersWorkIsRolledBack() {
    service.post(pix("chave-1", 10_000));
    var transactionTemplate = new TransactionTemplate(transactionManager);

    assertThrows(IdempotencyConflictException.class, () ->
            transactionTemplate.executeWithoutResult(status -> {
              accountRepository.save(new Account(UUID.randomUUID(), "Fantasma", "BRL", AccountType.ASSET));
              service.post(pix("chave-1", 99_999));
            }));

    assertEquals(2, rowCount("account"), "a conta criada antes do conflito não pode commitar");
  }

  @Test
  void rejectsBlankIdempotencyKey() {
    assertThrows(IllegalArgumentException.class, () ->
            service.post(new PostTransactionCommand("Pix", "  ", List.of(
                    new PostTransactionCommand.RequestedEntry(checking.id(), Direction.DEBIT, 100),
                    new PostTransactionCommand.RequestedEntry(revenue.id(), Direction.CREDIT, 100)))));
  }

  private PostTransactionCommand pix(String idempotencyKey, long cents) {
    return new PostTransactionCommand("Pix", idempotencyKey, List.of(
            new PostTransactionCommand.RequestedEntry(checking.id(), Direction.DEBIT, cents),
            new PostTransactionCommand.RequestedEntry(revenue.id(), Direction.CREDIT, cents)));
  }

  private Account anAccount(String name, AccountType type) {
    var account = new Account(UUID.randomUUID(), name, "BRL", type);
    accountRepository.save(account);
    return account;
  }

  private long rowCount(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }
}
