package com.metabion.repository;

import com.metabion.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    UserRepository users;

    @Test
    void uniqueEmailConstraintRejectsDuplicateEmail() {
        users.save(buildUser("a@x.com"));

        assertThatThrownBy(() -> users.saveAndFlush(buildUser("a@x.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByEmailReturnsUserWithRoles() {
        var user = buildUser("b@x.com");
        user.addRole("PATIENT");
        users.save(user);

        var loaded = users.findByEmail("b@x.com").orElseThrow();

        assertThat(loaded.roleNames()).containsExactly("PATIENT");
    }

    @Test
    void emailIsNormalizedBeforePersisting() {
        users.saveAndFlush(buildUser("  C@X.COM  "));

        assertThat(users.findByEmail("c@x.com")).isPresent();
    }

    private static User buildUser(String email) {
        return new User(email, "hash");
    }
}
