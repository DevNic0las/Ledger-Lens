-- Unicidade de negócio da conta: o nome identifica a conta dentro de uma moeda.
-- Moedas diferentes podem repetir o nome ("Caixa" em BRL e "Caixa" em USD).
--
-- Vira constraint nomeada para que o INSERT possa alvejá-la explicitamente em
-- ON CONFLICT (name, currency) DO NOTHING, do mesmo jeito que a idempotência da
-- transação é decidida pela constraint. Colisão de PK continua sendo outra coisa.
ALTER TABLE account
    ADD CONSTRAINT account_name_currency_key UNIQUE (name, currency);
