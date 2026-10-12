package com.amazeawn.api.auth;

import com.amazeawn.api.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates (or re-points) the admin account from ADMIN_EMAIL / ADMIN_PASSWORD at startup. */
@Component
@Order(1)
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public AdminSeeder(UserRepository users, PasswordEncoder encoder, AppProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(org.springframework.boot.ApplicationArguments args) {
        AppProperties.Admin admin = props.admin();
        users.findByEmailIgnoreCase(admin.email()).ifPresentOrElse(existing -> {
            if (!encoder.matches(admin.password(), existing.getPasswordHash())) {
                existing.setPasswordHash(encoder.encode(admin.password()));
                log.info("Admin password updated for {}", admin.email());
            }
        }, () -> {
            users.save(new User(admin.email().toLowerCase(), encoder.encode(admin.password()), admin.name(), Role.ADMIN));
            log.info("Seeded admin account {}", admin.email());
        });
    }
}
