package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.TestResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TestResultRepository extends JpaRepository<TestResult, UUID> {
}
