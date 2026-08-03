package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    Page<Project> findAllByOwner(User owner, Pageable pageable);
}
