package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.TestExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TestExecutionRepository extends JpaRepository<TestExecution, UUID> {
}
