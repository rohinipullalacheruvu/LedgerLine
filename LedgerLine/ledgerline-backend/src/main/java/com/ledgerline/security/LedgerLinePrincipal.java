package com.ledgerline.security;

import com.ledgerline.entity.UserEntity;
import com.ledgerline.enums.UserRole;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class LedgerLinePrincipal implements UserDetails {

    private final Long userId;
    private final String username;
    private final String passwordHash;
    private final UserRole role;
    private final long tokenVersion;
    private final boolean enabled;

    private LedgerLinePrincipal(UserEntity user) {
        this.userId = user.getId();
        this.username = user.getUsername();
        this.passwordHash = user.getPasswordHash();
        this.role = user.getRole();
        this.tokenVersion = user.getTokenVersion();
        this.enabled = user.isEnabled();
    }

    public static LedgerLinePrincipal from(UserEntity user) {
        return new LedgerLinePrincipal(user);
    }

    public Long getUserId() {
        return userId;
    }

    public UserRole getRole() {
        return role;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
