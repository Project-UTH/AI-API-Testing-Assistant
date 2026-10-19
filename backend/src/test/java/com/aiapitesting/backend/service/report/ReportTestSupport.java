package com.aiapitesting.backend.service.report;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;

import java.time.Instant;
import java.util.UUID;

/** Dựng dữ liệu mẫu cho test báo cáo (Module 17 - 17d). */
final class ReportTestSupport {

    private ReportTestSupport() {
    }

    static Project project(String name) {
        return Project.builder().id(UUID.randomUUID()).name(name).build();
    }

    static TestExecution execution(ExecutionStatus status) {
        return TestExecution.builder().id(UUID.randomUUID()).status(status).startedAt(Instant.parse("2026-10-19T10:00:00Z"))
                .finishedAt(Instant.parse("2026-10-19T10:01:00Z")).targetBaseUrlSnapshot("http://target:8081").build();
    }

    static TestResult result(String method, String path, String name, TestResultStatus status, Integer actual, int expected,
                             FailureCause cause, String evidenceJson) {
        Endpoint endpoint = Endpoint.builder().id(UUID.randomUUID()).method(method).path(path).build();
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).name(name).expectedStatus(expected)
                // Cố ý có dữ liệu nhạy cảm ở các field KHÔNG được đưa vào báo cáo:
                .requestHeaders("{\"Authorization\":\"Bearer SECRET-REQUEST-HEADER-TOKEN\"}")
                .requestBody("{\"password\":\"SECRET-REQUEST-BODY-PASSWORD\"}").build();
        return TestResult.builder().id(UUID.randomUUID()).testCase(testCase).status(status).responseStatus(actual)
                .responseBody("{\"leak\":\"SECRET-RESPONSE-BODY\"}").durationMs(120L).failureCause(cause).failureEvidence(evidenceJson).build();
    }
}
