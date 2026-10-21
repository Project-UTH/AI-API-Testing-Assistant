package com.mutantapi.mutant;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tap mutant dang bat (luc chay). Rong = ban chuan. An toan khi nhieu request chay song song.
 * Gom 2 loai: loi viet tay ({@link MutantFlag}) va "cong tac quy tac" ({@link RuleCatalog}) - tat dung 1 kiem tra dau vao/xac thuc.
 */
@Component
public class Mutants {

    private static volatile Mutants instance;

    private final Set<MutantFlag> enabled = ConcurrentHashMap.newKeySet();
    private final Set<String> disabledRules = ConcurrentHashMap.newKeySet();

    public Mutants() {
        instance = this;
    }

    public boolean on(MutantFlag flag) {
        return enabled.contains(flag);
    }

    public void set(Collection<MutantFlag> flags) {
        enabled.clear();
        enabled.addAll(flags);
    }

    public Set<MutantFlag> enabled() {
        Set<MutantFlag> copy = EnumSet.noneOf(MutantFlag.class);
        copy.addAll(enabled);
        return copy;
    }

    public void setRules(Collection<String> ruleIds) {
        disabledRules.clear();
        disabledRules.addAll(ruleIds);
    }

    public Set<String> rules() {
        return new TreeSet<>(disabledRules);
    }

    /** true neu kiem tra (endpoint, field, kind) dang bi TAT boi cong tac quy tac. Luon false o ban chuan. */
    public static boolean ruleOff(String endpoint, String field, String kind) {
        Mutants m = instance;
        return m != null && !m.disabledRules.isEmpty() && endpoint != null
                && m.disabledRules.contains(RuleCatalog.id(endpoint, field, kind));
    }
}
