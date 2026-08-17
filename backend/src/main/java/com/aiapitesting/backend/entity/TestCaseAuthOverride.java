package com.aiapitesting.backend.entity;

/**
 * Ghi đè cách gắn auth target API cho riêng 1 TestCase - dùng cho nhóm Security (Module 9a) cần cố
 * tình gửi request thiếu/sai auth, khác với hành vi mặc định luôn gắn đúng auth thật của Project.
 */
public enum TestCaseAuthOverride {
    /** Hành vi mặc định - gắn auth thật giải mã từ Project.targetAuthType/targetAuthValueEncrypted. */
    DEFAULT,
    /** Không gắn header auth nào cả, dù Project có cấu hình auth. */
    NONE,
    /** Gắn đúng tên header theo TargetAuthType của Project nhưng với giá trị cố tình sai. */
    INVALID
}
