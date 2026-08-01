package com.labjudge.server.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.labjudge.server.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}
