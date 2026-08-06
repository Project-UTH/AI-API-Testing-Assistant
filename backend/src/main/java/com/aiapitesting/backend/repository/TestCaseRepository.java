package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {
    void deleteAllByEndpoint(Endpoint endpoint);
}
