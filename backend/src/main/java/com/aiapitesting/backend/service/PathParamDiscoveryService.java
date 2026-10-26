package com.aiapitesting.backend.service;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.service.execution.RestAssuredTestRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tự tìm giá trị THẬT cho tham số đường dẫn ({id}) khi người dùng chưa nhập và chưa có test POST tạo tài nguyên để nối chuỗi: gọi 1 lệnh GET (chỉ đọc,
 * không ghi) vào endpoint DANH SÁCH cùng tài nguyên (đường dẫn cha, vd /api/candidates/resumes cho /api/candidates/resumes/{id}/reparse) và lấy id của phần tử
 * đầu tiên. Không gọi AI, 0 token. Nội dung phản hồi KHÔNG được ghi log (chỉ id đã kiểm định dạng được dùng).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PathParamDiscoveryService {

    /** Giá trị id hợp lệ để đưa vào đường dẫn: chữ, số, gạch, chấm, hai chấm; tối đa 100 ký tự (chặn ký tự điều khiển đường dẫn như / ? #). */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");
    private static final String[] LIST_KEYS = {"content", "data", "items", "results", "records", "rows"};

    private final EndpointRepository endpointRepository;
    private final RestAssuredTestRunner runner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** @param value giá trị id tìm được (null nếu không); @param note mô tả ngắn thứ đã thử, dùng cho bước giám sát/giải thích khi không tìm được */
    public record Discovery(String value, String note) {
    }

    public Discovery discover(Project project, Endpoint endpoint, String paramName) {
        String candidatePath = DependencySuggestionService.cutPathBeforeParam(endpoint.getPath(), paramName);
        if (candidatePath == null || candidatePath.isBlank() || candidatePath.contains("{")) {
            return new Discovery(null, "không có endpoint danh sách đơn giản cho tham số " + paramName);
        }
        Optional<Endpoint> list = endpointRepository.findByProjectAndPathAndMethod(project, candidatePath, "GET");
        if (list.isEmpty()) {
            return new Discovery(null, "không có GET " + candidatePath + " (danh sách) trong spec");
        }
        String label = "GET " + candidatePath;
        try {
            TestCase probe = TestCase.builder().endpoint(list.get()).name("Tự tìm id thật").resolvedPath(candidatePath).expectedStatus(200).build();
            RestAssuredTestRunner.RunResult result = runner.run(project, probe, Map.of());
            if (result.statusCode() < 200 || result.statusCode() > 299) {
                String api = apiError(result.responseBody());
                return new Discovery(null, label + " trả " + result.statusCode() + (api != null ? " (" + api + ")"
                        : result.statusCode() == 401 || result.statusCode() == 403 ? " (cần token/quyền phù hợp)" : ""));
            }
            String id = firstId(result.responseBody(), paramName);
            return id == null ? new Discovery(null, label + " không có phần tử nào (hệ thống đích chưa có dữ liệu)") : new Discovery(id, label);
        } catch (RuntimeException e) {
            log.warn("Tu tim id that that bai: {}", e.getClass().getSimpleName());
            return new Discovery(null, label + " không gọi được");
        }
    }

    /** "MA_LOI: thông điệp" từ phản hồi lỗi JSON của API đích (cắt ngắn); null nếu không có. */
    String apiError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode json = objectMapper.readTree(body);
            String code = json.path("error").asText(json.path("code").asText(""));
            String message = json.path("message").asText("");
            String text = code.isBlank() ? message : message.isBlank() ? code : code + ": " + message;
            return text.isBlank() ? null : text.length() > 160 ? text.substring(0, 160) : text;
        } catch (Exception e) {
            return null;
        }
    }

    /** id của phần tử đầu tiên: JSON là mảng, hoặc đối tượng có mảng ở khoá thường gặp (content/data/items/...). Ưu tiên khoá "id", rồi tên tham số. */
    String firstId(String body, String paramName) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode list = root.isArray() ? root : null;
            if (list == null && root.isObject()) {
                for (String key : LIST_KEYS) {
                    if (root.path(key).isArray()) {
                        list = root.get(key);
                        break;
                    }
                }
                if (list == null) {
                    for (JsonNode child : root) {
                        if (child.isArray()) {
                            list = child;
                            break;
                        }
                    }
                }
            }
            if (list == null || list.isEmpty()) {
                return null;
            }
            JsonNode first = list.get(0);
            for (String key : new String[]{"id", paramName, "uuid"}) {
                JsonNode v = first.path(key);
                if (!v.isMissingNode() && !v.isNull() && (v.isTextual() || v.isNumber())) {
                    String text = v.asText();
                    if (SAFE_ID.matcher(text).matches()) {
                        return text;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
