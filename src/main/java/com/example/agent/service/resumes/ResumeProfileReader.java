package com.example.agent.service.resumes;

import com.example.agent.entity.CandidateResumeEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/** Validate the Java/Python boundary before updating any candidate fields. */
@Component
@RequiredArgsConstructor
public class ResumeProfileReader {
    private final ObjectMapper json;

    public CandidateResumeEntity read(JsonNode profile) {
        if (profile == null || !profile.isObject() || !profile.path("skills").isArray()
                || profile.path("skills").size() > 15) throw invalid();
        var skills = new ArrayList<String>();
        for (var skill : profile.path("skills")) {
            if (!skill.isTextual() || skill.asText().length() > 100) throw invalid();
            String text = skill.asText().trim();
            if (!text.isEmpty() && !skills.contains(text)) skills.add(text);
        }
        var result = new CandidateResumeEntity();
        result.setCandidateName(text(profile, "candidate_name", 100));
        result.setEmail(text(profile, "email", 100));
        result.setEducation(text(profile, "education", 50));
        result.setLastCompany(text(profile, "last_company", 200));
        var years = profile.get("years_exp");
        if (years != null && !years.isNull()) {
            if (!years.isIntegralNumber() || !years.canConvertToInt() || years.intValue() < 0 || years.intValue() > 80) throw invalid();
            result.setYearsExp(years.intValue());
        }
        try { result.setSkills(json.writeValueAsString(skills)); }
        catch (Exception e) { throw invalid(); }
        return result;
    }

    private String text(JsonNode profile, String name, int max) {
        var value = profile.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().length() > max) throw invalid();
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
    private ResumeParseFailure invalid() {
        return new ResumeParseFailure("INVALID_PROFILE", "模型返回的简历字段不符合要求，请确认后重新解析");
    }
}
