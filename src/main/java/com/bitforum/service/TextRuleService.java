package com.bitforum.service;

import com.bitforum.exception.BusinessException;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bitforum.entity.TextRule;
import com.bitforum.entity.TextRuleOperation;
import com.bitforum.mapper.TextRuleMapper;
import com.bitforum.mapper.TextRuleOperationMapper;

@Service
public class TextRuleService {
    @Autowired
    private TextRuleMapper ruleMapper;
    @Autowired
    private TextRuleOperationMapper operationMapper;

    public record Match(long version, List<String> phrases) {
        public boolean hit() {
            return !phrases.isEmpty();
        }
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(codePoint -> Character.getType(codePoint) != Character.FORMAT
                        && codePoint != 0xFE0E && codePoint != 0xFE0F)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    public Match match(String value) {
        String text = normalize(value);
        long version = currentVersion();
        if (text.isBlank()) {
            return new Match(version, List.of());
        }
        List<String> matches = ruleMapper.selectList(new QueryWrapper<TextRule>().eq("enabled", 1)).stream()
                .filter(rule -> text.contains(rule.getNormalizedPhrase()))
                .map(TextRule::getPhrase)
                .toList();
        return new Match(version, matches);
    }

    public List<TextRule> listRules() {
        return ruleMapper.selectList(new QueryWrapper<TextRule>().orderByDesc("update_time", "id"));
    }

    public long currentVersion() {
        Long version = ruleMapper.currentVersion();
        return version == null ? 0 : version;
    }

    @Transactional
    public TextRule create(String phrase, Long actorId) {
        String clean = cleanPhrase(phrase);
        TextRule rule = new TextRule();
        rule.setPhrase(clean);
        rule.setNormalizedPhrase(normalize(clean));
        rule.setEnabled(1);
        rule.setCreatedBy(actorId);
        rule.setUpdatedBy(actorId);
        try {
            ruleMapper.insert(rule);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("规则已存在", e);
        }
        logChange(rule.getId(), actorId, "CREATE", null, rule);
        return rule;
    }

    @Transactional
    public TextRule update(Long ruleId, String phrase, Boolean enabled, Long actorId) {
        TextRule rule = requireRule(ruleId);
        TextRule before = copy(rule);
        if (phrase != null) {
            String clean = cleanPhrase(phrase);
            rule.setPhrase(clean);
            rule.setNormalizedPhrase(normalize(clean));
        }
        if (enabled != null) {
            rule.setEnabled(enabled ? 1 : 0);
        }
        if (rule.getPhrase().equals(before.getPhrase()) && rule.getEnabled().equals(before.getEnabled())) {
            return rule;
        }
        rule.setUpdatedBy(actorId);
        try {
            ruleMapper.updateById(rule);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("规则已存在", e);
        }
        logChange(ruleId, actorId, "UPDATE", before, rule);
        return rule;
    }

    @Transactional
    public void delete(Long ruleId, Long actorId) {
        TextRule before = requireRule(ruleId);
        ruleMapper.deleteById(ruleId);
        logChange(ruleId, actorId, "DELETE", before, null);
    }

    private TextRule requireRule(Long ruleId) {
        TextRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException("规则不存在");
        }
        return rule;
    }

    private String cleanPhrase(String phrase) {
        if (!StringUtils.hasText(phrase)) {
            throw new BusinessException("规则内容不能为空");
        }
        String clean = phrase.trim();
        if (clean.length() > 255 || normalize(clean).isBlank() || normalize(clean).length() > 255) {
            throw new BusinessException("规则内容无效或超过 255 字符");
        }
        return clean;
    }

    private void logChange(Long ruleId, Long actorId, String action, TextRule before, TextRule after) {
        ruleMapper.incrementVersion();
        TextRuleOperation operation = new TextRuleOperation();
        operation.setRuleId(ruleId);
        operation.setActorId(actorId);
        operation.setAction(action);
        operation.setOldPhrase(before == null ? null : before.getPhrase());
        operation.setNewPhrase(after == null ? null : after.getPhrase());
        operation.setOldEnabled(before == null ? null : before.getEnabled());
        operation.setNewEnabled(after == null ? null : after.getEnabled());
        operation.setRuleVersion(currentVersion());
        operationMapper.insert(operation);
    }

    private TextRule copy(TextRule source) {
        TextRule copy = new TextRule();
        copy.setPhrase(source.getPhrase());
        copy.setEnabled(source.getEnabled());
        return copy;
    }
}
