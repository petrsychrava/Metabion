package com.metabion.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "account_verification_tokens")
public class AccountVerification extends HashedToken {
}
