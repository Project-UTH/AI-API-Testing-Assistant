package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.dto.rule.RequirementDocumentDtos.ContentResponse;
import com.aiapitesting.backend.dto.rule.RequirementDocumentDtos.DocumentResponse;
import com.aiapitesting.backend.dto.rule.RequirementDocumentDtos.UsageEntry;
import com.aiapitesting.backend.entity.BusinessRule;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RequirementDocument;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.exception.RequirementDocumentException;
import com.aiapitesting.backend.repository.BusinessRuleRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.RequirementDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiapitesting.backend.repository.RequirementDocumentUsageRepository;
import com.aiapitesting.backend.service.CurrentUserService;
import com.aiapitesting.backend.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 17q G3.3 - tài liệu nghiệp vụ theo project: chỉ .md/.txt (kiểm theo TÊN, không tin Content-Type), ≤200.000 byte, ≤5 file đang dùng, UTF-8 nghiêm
 * ngặt. Nội dung được chuẩn hoá (bỏ BOM, CRLF→LF, NFC) rồi mới lưu - đây là mốc duy nhất cho kiểm chứng đoạn trích. Tải trùng tên = thay bản cũ
 * (luật chờ duyệt/đã duyệt của bản cũ thành STALE). Quét bí mật lúc tải để cảnh báo (số dòng + mã quy tắc, không lộ giá trị); bản gửi AI luôn được che.
 */
@Service
@RequiredArgsConstructor
public class RequirementDocumentService {

    public static final int MAX_BYTES = 200_000;
    /** Số tài liệu đang dùng tối đa mỗi project. */
    public static final int MAX_ACTIVE_DOCUMENTS = 30;
    /** Số tài liệu tối đa gán cho MỘT endpoint (mỗi tài liệu đều được gửi AI ở lượt của endpoint đó, nên giới hạn để không đội token). */
    public static final int MAX_DOCUMENTS_PER_ENDPOINT = 8;
    private static final int MAX_ENDPOINTS_PER_DOCUMENT = 300;

    private final ProjectService projectService;
    private final CurrentUserService currentUserService;
    private final RequirementDocumentRepository documentRepository;
    private final BusinessRuleRepository ruleRepository;
    private final RequirementDocumentUsageRepository usageRepository;
    private final EndpointRepository endpointRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public DocumentResponse upload(UUID projectId, String originalFileName, byte[] bytes) {
        Project project = projectService.getOwnedProject(projectId);
        String fileName = cleanFileName(originalFileName);
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".md") && !lower.endsWith(".txt")) {
            throw new RequirementDocumentException("DOCUMENT_TYPE_INVALID", HttpStatus.BAD_REQUEST, "Chỉ nhận tệp .md hoặc .txt");
        }
        if (bytes == null || bytes.length == 0) {
            throw new RequirementDocumentException("DOCUMENT_TYPE_INVALID", HttpStatus.BAD_REQUEST, "Tệp rỗng");
        }
        if (bytes.length > MAX_BYTES) {
            throw new RequirementDocumentException("DOCUMENT_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE,
                    "Tệp quá lớn (tối đa " + MAX_BYTES / 1000 + " KB)");
        }
        String content = normalize(decode(bytes));
        if (content.isBlank()) {
            throw new RequirementDocumentException("DOCUMENT_TYPE_INVALID", HttpStatus.BAD_REQUEST, "Tệp không có nội dung văn bản");
        }

        RequirementDocument previous = documentRepository.findFirstByProjectAndFileNameAndActiveTrue(project, fileName).orElse(null);
        if (previous == null && documentRepository.countByProjectAndActiveTrue(project) >= MAX_ACTIVE_DOCUMENTS) {
            throw new RequirementDocumentException("DOCUMENT_LIMIT_REACHED", HttpStatus.CONFLICT,
                    "Mỗi project tối đa " + MAX_ACTIVE_DOCUMENTS + " tài liệu - xoá bớt hoặc tải trùng tên để thay bản cũ");
        }
        if (previous != null) {
            previous.setActive(false);
            previous.setContent(null);
            documentRepository.save(previous);
            markRulesStale(project, previous.getId());
        }
        String carriedEndpoints = previous == null ? null : previous.getForEndpointsJson(); // thay bản: giữ khai báo "viết cho endpoint nào"
        RequirementDocument saved = documentRepository.save(RequirementDocument.builder()
                .project(project)
                .fileName(fileName)
                .sizeBytes(bytes.length)
                .sha256(sha256(content))
                .content(content)
                .uploadedBy(currentUserService.getCurrentUser().getId())
                .forEndpointsJson(carriedEndpoints)
                .build());
        return toResponse(saved);
    }

    /** Khai báo tài liệu viết cho endpoint nào. Chỉ nhận khoá endpoint có trong project (METHOD path); thay toàn bộ danh sách. */
    @Transactional
    public DocumentResponse setEndpoints(UUID projectId, UUID documentId, List<String> endpointKeys) {
        Project project = projectService.getOwnedProject(projectId);
        RequirementDocument doc = documentRepository.findByIdAndProject(documentId, project).orElseThrow(RequirementDocumentService::notFound);
        if (!doc.isActive()) {
            throw notFound();
        }
        List<String> keys = endpointKeys == null ? List.of() : endpointKeys.stream().filter(k -> k != null && !k.isBlank()).map(String::trim).distinct().toList();
        if (keys.size() > MAX_ENDPOINTS_PER_DOCUMENT) {
            throw new RequirementDocumentException("DOCUMENT_ENDPOINTS_INVALID", HttpStatus.BAD_REQUEST, "Tối đa " + MAX_ENDPOINTS_PER_DOCUMENT + " endpoint cho mỗi tài liệu");
        }
        java.util.Set<String> known = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project).stream().map(BusinessRuleService::keyOf)
                .collect(java.util.stream.Collectors.toSet());
        if (!known.containsAll(keys)) {
            throw new RequirementDocumentException("DOCUMENT_ENDPOINTS_INVALID", HttpStatus.BAD_REQUEST, "Có endpoint không thuộc project này");
        }
        try {
            doc.setForEndpointsJson(keys.isEmpty() ? null : objectMapper.writeValueAsString(keys));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return toResponse(documentRepository.save(doc));
    }

    private List<String> readEndpoints(RequirementDocument doc) {
        if (doc.getForEndpointsJson() == null || doc.getForEndpointsJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(doc.getForEndpointsJson(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> list(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        return documentRepository.findAllByProjectAndActiveTrueOrderByCreatedAtAsc(project).stream().map(this::toResponse).toList();
    }

    /**
     * Mỗi cặp (tài liệu, endpoint) đã được dùng cho AI: lần gần nhất và số lần. Tên tài liệu lấy từ tài liệu hiện có hoặc từ bản chụp lúc dùng
     * (tài liệu đã xoá/thay bản vẫn hiện, documentActive=false).
     */
    @Transactional(readOnly = true)
    public List<UsageEntry> usage(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        java.util.Map<UUID, RequirementDocument> docs = new java.util.HashMap<>();
        documentRepository.findAllByProjectOrderByCreatedAtAsc(project).forEach(d -> docs.put(d.getId(), d));
        return usageRepository.summarize(project.getId()).stream().map(row -> {
            RequirementDocument doc = docs.get(row.getDocumentId());
            String name = doc != null ? doc.getFileName() : "(tài liệu đã bị xoá)";
            return new UsageEntry(row.getDocumentId(), name, doc != null && doc.isActive(), row.getEndpointKey(), row.getLastUsedAt(), row.getTimesUsed());
        }).sorted(java.util.Comparator.comparing(UsageEntry::lastUsedAt).reversed()).toList();
    }

    /** Nội dung đã chuẩn hoá - giao diện dùng để tô sáng đoạn trích theo quoteOffset (không tự tìm chuỗi con). */
    @Transactional(readOnly = true)
    public ContentResponse content(UUID projectId, UUID documentId) {
        Project project = projectService.getOwnedProject(projectId);
        RequirementDocument doc = documentRepository.findByIdAndProject(documentId, project).orElseThrow(RequirementDocumentService::notFound);
        if (doc.getContent() == null) {
            throw new RequirementDocumentException("DOCUMENT_NOT_FOUND", HttpStatus.NOT_FOUND, "Tài liệu đã bị xoá hoặc đã được thay bản mới");
        }
        return new ContentResponse(doc.getId(), doc.getFileName(), doc.getContent());
    }

    @Transactional
    public void delete(UUID projectId, UUID documentId) {
        Project project = projectService.getOwnedProject(projectId);
        RequirementDocument doc = documentRepository.findByIdAndProject(documentId, project).orElseThrow(RequirementDocumentService::notFound);
        doc.setActive(false);
        doc.setContent(null); // luật đã trích giữ đoạn trích riêng (BusinessRule.quote) nên không đổi
        documentRepository.save(doc);
    }

    /** Tài liệu đang dùng của project (cho trích luật); mặc định tất cả, hoặc đúng các id được chọn. */
    @Transactional(readOnly = true)
    public List<RequirementDocument> activeDocuments(Project project, List<UUID> onlyIds) {
        return documentRepository.findAllByProjectAndActiveTrueOrderByCreatedAtAsc(project).stream()
                .filter(d -> d.getContent() != null)
                .filter(d -> onlyIds == null || onlyIds.isEmpty() || onlyIds.contains(d.getId()))
                .toList();
    }

    // ---- nội bộ ----

    private void markRulesStale(Project project, UUID oldDocumentId) {
        for (BusinessRule rule : ruleRepository.findAllByProject(project)) {
            if (oldDocumentId.equals(rule.getDocumentId())
                    && (rule.getStatus() == RuleStatus.PROPOSED || rule.getStatus() == RuleStatus.APPROVED)) {
                rule.setStatus(RuleStatus.STALE);
                rule.setStatusNote("Tài liệu nguồn đã được thay bằng bản mới - xem lại luật này theo tài liệu mới");
                ruleRepository.save(rule);
            }
        }
    }

    static String normalize(String text) {
        String s = text.startsWith("﻿") ? text.substring(1) : text;
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    private static String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\u0000') >= 0) {
                throw new RequirementDocumentException("DOCUMENT_ENCODING_INVALID", HttpStatus.BAD_REQUEST, "Tệp chứa ký tự NUL - không phải văn bản");
            }
            return text;
        } catch (CharacterCodingException e) {
            throw new RequirementDocumentException("DOCUMENT_ENCODING_INVALID", HttpStatus.BAD_REQUEST,
                    "Tệp không phải UTF-8 hợp lệ - lưu lại tệp ở dạng UTF-8 rồi tải lên");
        }
    }

    private static String cleanFileName(String original) {
        String name = original == null ? "" : java.text.Normalizer.normalize(original, java.text.Normalizer.Form.NFC)
                .replaceAll("\\p{Cntrl}", "").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) {
            throw new RequirementDocumentException("DOCUMENT_TYPE_INVALID", HttpStatus.BAD_REQUEST, "Thiếu tên tệp");
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private DocumentResponse toResponse(RequirementDocument doc) {
        List<DocumentResponse.Warning> warnings = doc.getContent() == null ? List.of()
                : TextSecretScanner.scan(doc.getContent()).stream().map(f -> new DocumentResponse.Warning(f.line(), f.rule())).toList();
        int lines = doc.getContent() == null ? 0 : (int) doc.getContent().lines().count();
        return new DocumentResponse(doc.getId(), doc.getFileName(), doc.getSizeBytes(), lines, doc.isActive(), doc.getCreatedAt(), warnings, readEndpoints(doc));
    }

    private static RequirementDocumentException notFound() {
        return new RequirementDocumentException("DOCUMENT_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy tài liệu với id đã cho");
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
