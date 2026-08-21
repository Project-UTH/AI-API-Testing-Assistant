package com.aiapitesting.backend.entity;

/** Độ ưu tiên xử lý bug (Module 10) - TRIVIAL đứng đầu làm mặc định (@Builder.Default). */
public enum BugPriority {
    TRIVIAL,
    MINOR,
    MAJOR,
    CRITICAL,
    BLOCKER
}
