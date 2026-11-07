package com.ledgerline.entity;

import com.ledgerline.enums.AccountRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "account_permissions", uniqueConstraints = @UniqueConstraint(
        name = "uq_account_permissions_account_user",
        columnNames = {"account_id", "user_id"}))
public class AccountPermissionEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private AccountEntity account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountRole role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by_user_id")
    private UserEntity grantedBy;

    protected AccountPermissionEntity() {
    }

    public AccountEntity getAccount() {
        return account;
    }

    public void setAccount(AccountEntity account) {
        this.account = account;
    }

    public UserEntity getUser() {
        return user;
    }

    public void setUser(UserEntity user) {
        this.user = user;
    }

    public AccountRole getRole() {
        return role;
    }

    public void setRole(AccountRole role) {
        this.role = role;
    }

    public UserEntity getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(UserEntity grantedBy) {
        this.grantedBy = grantedBy;
    }
}
