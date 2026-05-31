package com.metabion.repository;

import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class UserRepositoryTest {

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
        user.addRole(RoleName.PATIENT);
        users.save(user);

        var loaded = users.findByEmail("b@x.com").orElseThrow();

        assertThat(loaded.roleNames()).containsExactly("PATIENT");
        assertThat(loaded.hasRole(RoleName.PATIENT)).isTrue();
    }

    @Test
    void userCanHaveMultipleSupportedRoles() {
        var user = buildUser("d@x.com");
        user.addRole(RoleName.PHYSICIAN);
        user.addRole(RoleName.COORDINATOR);
        users.save(user);

        var loaded = users.findByEmail("d@x.com").orElseThrow();

        assertThat(loaded.roleNames()).containsExactly("COORDINATOR", "PHYSICIAN");
        assertThat(loaded.hasAnyRole(RoleName.PHYSICIAN, RoleName.COORDINATOR)).isTrue();
        assertThat(loaded.hasAnyRole(RoleName.PATIENT, RoleName.ADMIN)).isFalse();
    }

    @Test
    void unsupportedRoleIsRejected() {
        var user = buildUser("e@x.com");

        assertThatThrownBy(() -> user.addRole("USER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported role: USER");
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
