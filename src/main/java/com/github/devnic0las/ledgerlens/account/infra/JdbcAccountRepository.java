package com.github.devnic0las.ledgerlens.account.infra;

import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountRepository;
import com.github.devnic0las.ledgerlens.account.domain.AccountType;
import com.github.devnic0las.ledgerlens.account.domain.exception.AccountAlreadyExistsException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;


@Repository
public class JdbcAccountRepository implements AccountRepository {

  private final JdbcClient jdbc;

  public JdbcAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * {@code ON CONFLICT (name, currency) DO NOTHING} pelo mesmo motivo da idempotência da
   * transação: violar a {@code UNIQUE} abortaria a transação inteira do chamador (SQLState
   * {@code 25P02}), então o conflito é resolvido sem erro. A constraint de negócio é
   * nomeada no {@code ON CONFLICT}, logo colisão de PK ({@code account_pkey}) continua
   * subindo como {@code DuplicateKeyException} e não vira "conta já existe".
   */
  @Override
  public void save(Account account) {
    int inserted = jdbc.sql("""
                    INSERT INTO account (id, name, type, currency)
                    VALUES (:id, :name, :type, :currency)
                    ON CONFLICT (name, currency) DO NOTHING
                    """)
            .param("id", account.id())
            .param("name", account.name())
            .param("type", account.type().name())
            .param("currency", account.currency())
            .update();

    if (inserted == 0) {
      throw new AccountAlreadyExistsException(
              "Account already exists with name and currency: " + account.name() + ", " + account.currency());
    }
  }

  /**
   * Uma única query para todos os ids. Conjunto vazio devolve mapa vazio sem ir ao banco:
   * {@code WHERE id IN ()} é SQL inválido no Postgres.
   */
  @Override
  public Map<UUID, Account> findAllByIds(Set<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }

    return jdbc.sql("""
                    SELECT id, name, currency, type
                    FROM account
                    WHERE id IN (:ids)
                    """)
            .param("ids", ids)
            .query(this::mapAccount)
            .list()
            .stream()
            .collect(Collectors.toMap(Account::id, Function.identity()));
  }

  @Override
  public Optional<Account> findById(UUID id) {
    return jdbc.sql("""
                    SELECT id, name, currency, type
                    FROM account
                    WHERE id = :id
                    """)
            .param("id", id)
            .query(this::mapAccount)
            .optional();
  }

  /**
   * Ordem estável por {@code name, currency} — a mesma que a constraint de unicidade usa.
   * Não há coluna de data de criação: ordenar por {@code id} seria arbitrário e variaria a
   * cada chamada.
   */
  @Override
  public List<Account> findAll() {
    return jdbc.sql("""
                    SELECT id, name, currency, type
                    FROM account
                    ORDER BY name, currency
                    """)
            .query(this::mapAccount)
            .list();
  }

  private Account mapAccount(ResultSet rs, int rowNum) throws SQLException {
    return new Account(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("currency"),
            AccountType.valueOf(rs.getString("type")));
  }
}
