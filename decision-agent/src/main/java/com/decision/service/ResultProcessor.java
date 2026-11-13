package com.decision.service;

import com.decision.model.SearchModels.SearchItem;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ResultProcessor {

    private static final double MIN_RANK = 0.4;
    private static final double TITLE_SIMILARITY = 0.85;

    public List<SearchItem> filter(List<SearchItem> raw) {
        return raw.stream()
                .filter(i -> i.rankScore() >= MIN_RANK)
                .filter(i -> i.title() != null && !i.title().isBlank())
                .filter(i -> i.url() != null && !i.url().isBlank())
                .toList();
    }

    public int mergeInto(List<SearchItem> pool, List<SearchItem> candidates) {
        int added = 0;
        Set<String> candidateUrls = new HashSet<>();
        for (SearchItem cand : candidates) {
            if (candidateUrls.contains(cand.url().toLowerCase())) {
                continue;
            }
            boolean dup = false;
            for (int i = 0; i < pool.size(); i++) {
                SearchItem exist = pool.get(i);
                if (exist.url().equalsIgnoreCase(cand.url())) {
                    dup = true;
                    break;
                }
                if (similarTitle(exist.title(), cand.title())) {
                    if (cand.rankScore() > exist.rankScore()) {
                        pool.set(i, cand);
                        candidateUrls.add(cand.url().toLowerCase());
                    }
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                pool.add(cand);
                candidateUrls.add(cand.url().toLowerCase());
                added++;
            }
        }
        return added;
    }

    private boolean similarTitle(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.strip();
        String y = b.strip();
        if (x.equals(y)) return true;
        if (x.length() < 4 || y.length() < 4) return false;
        Set<String> ga = bigrams(x);
        Set<String> gb = bigrams(y);
        if (ga.isEmpty() || gb.isEmpty()) return false;
        Set<String> inter = new HashSet<>(ga);
        inter.retainAll(gb);
        Set<String> union = new HashSet<>(ga);
        union.addAll(gb);
        return (double) inter.size() / union.size() > TITLE_SIMILARITY;
    }

    private Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < s.length() - 1; i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }
}
