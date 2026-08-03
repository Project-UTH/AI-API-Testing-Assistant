package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Endpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {
}
