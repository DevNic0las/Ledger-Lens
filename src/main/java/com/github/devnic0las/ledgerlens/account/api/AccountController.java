package com.github.devnic0las.ledgerlens.account.api;

import com.github.devnic0las.ledgerlens.account.application.AccountBalanceService;
import com.github.devnic0las.ledgerlens.account.application.AccountQueryService;
import com.github.devnic0las.ledgerlens.account.application.CreateAccountCommand;
import com.github.devnic0las.ledgerlens.account.application.CreateAccountService;
import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Contas: criação e leitura.
 *
 * <ul>
 *   <li>{@code POST /accounts} — {@code 201} com o id gerado e {@code Location} em
 *       {@code /accounts/{id}}; {@code 409} se {@code (name, currency)} já existir;
 *       {@code 400} para corpo inválido.</li>
 *   <li>{@code GET /accounts} — {@code 200} com a lista; lista vazia é {@code []}, não 404.</li>
 *   <li>{@code GET /accounts/{id}} — {@code 200}, {@code 404} se não existir, {@code 400} se o
 *       id da URL não for UUID.</li>
 *   <li>{@code GET /accounts/{id}/balance} — saldo derivado, em centavos. Mesmos status.</li>
 * </ul>
 */
@RestController
@RequestMapping("/accounts")
class AccountController {

  private final AccountBalanceService balanceService;
  private final AccountQueryService queryService;
  private final CreateAccountService createService;

  AccountController(AccountBalanceService balanceService,
                    AccountQueryService queryService,
                    CreateAccountService createService) {
    this.balanceService = balanceService;
    this.queryService = queryService;
    this.createService = createService;
  }

  @PostMapping
  ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
    Account account = createService.create(request.toCommand());

    return ResponseEntity
            .created(URI.create("/accounts/" + account.id()))
            .body(AccountResponse.of(account));
  }

  @GetMapping
  ResponseEntity<List<AccountResponse>> list() {
    return ResponseEntity.ok(queryService.findAll().stream().map(AccountResponse::of).toList());
  }

  @GetMapping("/{id}")
  ResponseEntity<AccountResponse> byId(@PathVariable UUID id) {
    return ResponseEntity.ok(AccountResponse.of(queryService.findById(id)));
  }

  @GetMapping("/{id}/balance")
  ResponseEntity<BalanceResponse> balance(@PathVariable UUID id) {
    return ResponseEntity.ok(new BalanceResponse(id, balanceService.balanceOf(id)));
  }

  /**
   * Corpo de criação. {@code currency} é validado na forma ({@code [A-Z]{3}}) e {@code type}
   * como enum: pedido malformado responde 400 antes de qualquer escrita.
   */
  record CreateAccountRequest(@NotBlank String name,
                              @NotBlank @Pattern(regexp = "[A-Z]{3}",
                                      message = "must be three upper-case letters (ISO 4217)") String currency,
                              @NotNull AccountType type) {

    CreateAccountCommand toCommand() {
      return new CreateAccountCommand(name, currency, type);
    }
  }

  record AccountResponse(UUID id, String name, String currency, String type) {

    static AccountResponse of(Account account) {
      return new AccountResponse(account.id(), account.name(), account.currency(), account.type().name());
    }
  }

  record BalanceResponse(UUID accountId, long balanceCents) {
  }
}
