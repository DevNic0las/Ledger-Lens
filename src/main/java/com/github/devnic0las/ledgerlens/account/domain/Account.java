package com.github.devnic0las.ledgerlens.account.domain;

import java.util.UUID;

public record Account(UUID id, String name, String currency, AccountType type){}
