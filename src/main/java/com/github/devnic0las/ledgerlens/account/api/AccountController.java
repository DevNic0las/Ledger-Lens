package com.github.devnic0las.ledgerlens.account.api;

import com.github.devnic0las.ledgerlens.account.application.AccountBalanceService;
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
import java.util.UUID;

/**
 * {@code POST /accounts}: cria uma conta e devolve {@code 201} com o id gerado pelo servidor.
 *
 * <ul>
 *   <li>{@code 201} — criada. O {@code Location} aponta para {@code /accounts/{id}}.</li>
 *   <li>{@code 409} — já existe conta com o mesmo {@code (name, currency)}. O conflito vem da
 *       constraint {@code account_name_currency_key}, não de uma pré-consulta.</li>
 *   <li>{@code 400} — corpo inválido: campo ausente ou em branco, {@code currency} fora de
 *       três maiúsculas (ISO 4217), {@code type} desconhecido.</li>
 * </ul>
 *
 * <p>O pedido não traz {@code id}: o servidor gera, então o cliente não colide com PK.
 */
@RestController
@RequestMapping("/accounts")
class AccountController {

  private final AccountBalanceService balanceService;
  private final CreateAccountService createService;

  AccountController(AccountBalanceService balanceService, CreateAccountService createService) {
    this.balanceService = balanceService;
    this.createService = createService;
  }

  @PostMapping
  ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
    Account account = createService.create(request.toCommand());

    return ResponseEntity
            .created(URI.create("/accounts/" + account.id()))
            .body(AccountResponse.of(account));
  }

  @GetMapping("/{id}/balance")
  ResponseEntity<BalanceResponse> balance(@PathVariable UUID id) {
    return ResponseEntity.ok(new BalanceResponse(id, balanceService.balanceOf(id)));
  }

  /**
   * Corpo de criação. {@code currency} é validado na forma ({@code [A-Z]{3}}) e {@code type}
   * como enum: pedido malformado responde 400 antes de qualquer escrita, e não depende de uma
   * exceção do domínio.
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
