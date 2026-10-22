package com.mutantapi.store;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Du lieu trong bo nho (khong can DB ngoai) - /__reset xoa sach job/don, giu tai khoan seed. */
@Component
public class Store {

    public static final String DEMO_PASSWORD = "Passw0rd!";

    public record User(long id, String email, String passwordHash, String fullName, String role) {
    }

    public record Criterion(String name, BigDecimal weight, int maxScore) {
    }

    public static class Job {
        public long id;
        public long ownerId;
        public String title;
        public String description;
        public BigDecimal salaryMin;
        public BigDecimal salaryMax;
        public String salaryCurrency;
        public String jobType;
        public LocalDate deadline;
        public String status = "OPEN";
        public List<Criterion> rubric = new ArrayList<>();
        public Instant createdAt = Instant.now();
    }

    public static class Application {
        public long id;
        public long jobId;
        public long candidateId;
        public String coverLetter;
        public String status = "PENDING";
        public Instant createdAt = Instant.now();
    }

    private final Map<Long, User> users = new ConcurrentHashMap<>();
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private final Map<Long, Application> applications = new ConcurrentHashMap<>();
    private final AtomicLong jobSeq = new AtomicLong();
    private final AtomicLong applicationSeq = new AtomicLong();

    public Store() {
        // 2 HR (cong ty khac nhau) + 2 ung vien: du de gieo/kiem loi phan quyen theo chu so huu.
        seed(1, "hr1@demo.local", "Nguyen Van HR Mot", "HR");
        seed(2, "hr2@demo.local", "Tran Thi HR Hai", "HR");
        seed(3, "cand1@demo.local", "Le Van Ung Vien Mot", "CANDIDATE");
        seed(4, "cand2@demo.local", "Pham Thi Ung Vien Hai", "CANDIDATE");
    }

    private void seed(long id, String email, String fullName, String role) {
        users.put(id, new User(id, email, hash(DEMO_PASSWORD), fullName, role));
    }

    public static String hash(String password) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<User> findUserByEmail(String email) {
        return users.values().stream().filter(u -> u.email().equalsIgnoreCase(email)).findFirst();
    }

    public java.util.List<String> allEmails() {
        return users.values().stream().map(User::email).sorted().toList();
    }

    public Optional<User> findUser(long id) {
        return Optional.ofNullable(users.get(id));
    }

    public Job saveJob(Job job) {
        if (job.id == 0) {
            job.id = jobSeq.incrementAndGet();
        }
        jobs.put(job.id, job);
        return job;
    }

    /** Job chua bi xoa mem. */
    public Optional<Job> findJob(long id) {
        return Optional.ofNullable(jobs.get(id)).filter(j -> !"DELETED".equals(j.status));
    }

    /** Ke ca job da bi xoa mem - de don ung tuyen cu van tra cuu duoc chu so huu job. */
    public Optional<Job> findJobAny(long id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public Application saveApplication(Application application) {
        if (application.id == 0) {
            application.id = applicationSeq.incrementAndGet();
        }
        applications.put(application.id, application);
        return application;
    }

    public Optional<Application> findApplication(long id) {
        return Optional.ofNullable(applications.get(id));
    }

    public boolean hasApplication(long jobId, long candidateId) {
        return applications.values().stream().anyMatch(a -> a.jobId == jobId && a.candidateId == candidateId);
    }

    public boolean hasApplicationWithStatus(long jobId, long candidateId, String status) {
        return applications.values().stream()
                .anyMatch(x -> x.jobId == jobId && x.candidateId == candidateId && status.equals(x.status));
    }

    public void reset() {
        jobs.clear();
        applications.clear();
        jobSeq.set(0);
        applicationSeq.set(0);
    }
}
