package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.rule.RequirementDocumentDtos.ContentResponse;
import com.aiapitesting.backend.dto.rule.RequirementDocumentDtos.DocumentResponse;
import com.aiapitesting.backend.exception.RequirementDocumentException;
import com.aiapitesting.backend.service.rule.RequirementDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** 17q G3.3 - tài liệu nghiệp vụ (.md/.txt) của project, nguồn cho AI trích luật. Controller mỏng: mọi kiểm tra ở service. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/requirement-documents")
@RequiredArgsConstructor
public class RequirementDocumentController {

    private final RequirementDocumentService documentService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentResponse>> upload(@PathVariable UUID projectId, @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.of(documentService.upload(projectId, file.getOriginalFilename(), file.getBytes())));
        } catch (IOException e) {
            throw new RequirementDocumentException("DOCUMENT_ENCODING_INVALID", HttpStatus.BAD_REQUEST, "Không đọc được tệp tải lên");
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DocumentResponse>>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.of(documentService.list(projectId)));
    }

    @GetMapping("/{documentId}/content")
    public ResponseEntity<ApiResponse<ContentResponse>> content(@PathVariable UUID projectId, @PathVariable UUID documentId) {
        return ResponseEntity.ok(ApiResponse.of(documentService.content(projectId, documentId)));
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID documentId) {
        documentService.delete(projectId, documentId);
        return ResponseEntity.noContent().build();
    }
}
