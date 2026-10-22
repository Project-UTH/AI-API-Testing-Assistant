package com.mutantapi.mutant;

import com.mutantapi.store.Store;
import com.mutantapi.web.ApiException;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dieu khien mutant luc chay. CHI ton tai khi mutant.control=true (MUTANT_CONTROL=true) va AN khoi OpenAPI
 * (@Hidden) de cong cu sinh test khong sinh test cho chinh endpoint dieu khien nay.
 */
@RestController
@Hidden
@ConditionalOnProperty(name = "mutant.control", havingValue = "true")
public class MutantControlController {

    private final Mutants mutants;
    private final Store store;

    public MutantControlController(Mutants mutants, Store store) {
        this.mutants = mutants;
        this.store = store;
    }

    @GetMapping("/__mutants")
    public Map<String, Object> state() {
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (MutantFlag f : MutantFlag.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", f.name());
            item.put("category", f.category().name());
            item.put("categoryLabel", f.category().label());
            item.put("endpoint", f.endpoint());
            item.put("description", f.description());
            item.put("expectChecklist", f.expectChecklist());
            item.put("inReport", f.inReport());
            item.put("heldout", f.heldout());
            catalog.add(item);
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        for (RuleCatalog.Rule r : RuleCatalog.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.id());
            item.put("endpoint", r.endpoint());
            item.put("field", r.field());
            item.put("kind", r.kind());
            item.put("description", r.description());
            rules.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", mutants.enabled().stream().map(Enum::name).toList());
        body.put("catalog", catalog);
        body.put("rulesEnabled", mutants.rules());
        body.put("ruleCatalog", rules);
        return body;
    }

    /**
     * Body: {"enabled":["M01","M07"],"rules":["POST /auth/login|email|required"]} - thay toan bo tap dang bat; [] = ban chuan.
     * Neu khong gui "rules" thi cac cong tac quy tac cung duoc tat (script do chi gui "enabled" nen luon bat dau tu trang thai sach).
     */
    @PostMapping("/__mutants")
    public Map<String, Object> set(@RequestBody Map<String, List<String>> body) {
        List<String> ruleIds = body.getOrDefault("rules", List.of());
        java.util.Set<String> known = new java.util.HashSet<>();
        RuleCatalog.all().forEach(r -> known.add(r.id()));
        for (String id : ruleIds) {
            if (!known.contains(id)) {
                throw new ApiException(400, "UNKNOWN_RULE", "Cong tac quy tac khong ton tai: " + id);
            }
        }
        mutants.setRules(ruleIds);
        List<MutantFlag> flags = new ArrayList<>();
        for (String id : body.getOrDefault("enabled", List.of())) {
            try {
                flags.add(MutantFlag.valueOf(id));
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, "UNKNOWN_MUTANT", "Mutant khong ton tai: " + id);
            }
        }
        mutants.set(flags);
        return Map.of("enabled", mutants.enabled().stream().map(Enum::name).toList(), "rulesEnabled", mutants.rules());
    }

    /** Tat het mutant va xoa job/don (giu tai khoan seed) - dua target ve trang thai ban dau giua cac lan do. */
    @PostMapping("/__reset")
    public Map<String, Object> reset() {
        mutants.set(List.of());
        mutants.setRules(List.of());
        store.reset();
        return Map.of("reset", true);
    }
}
