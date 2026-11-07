package com.example.agent.service.materials;

import com.example.agent.entity.CandidateReportEntity;
import com.example.agent.entity.InterviewQuestionEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class MaterialOutputValidator {
    public void questions(List<InterviewQuestionEntity> questions) {
        if (questions == null || questions.size()!=6) throw invalid();
        var distinct = new HashSet<String>();
        for (var q:questions) {
            if (q.getCategory()==null || q.getDifficulty()==null || !Set.of("技术","项目","行为").contains(q.getCategory())
                    || !Set.of("EASY","MEDIUM","HARD").contains(q.getDifficulty())) throw invalid();
            text(q.getQuestion(),5000); text(q.getAssessPoint(),500); text(q.getReferenceAnswer(),10000);
            if (!distinct.add(q.getQuestion().trim())) throw invalid();
        }
        if (questions.stream().filter(q -> "技术".equals(q.getCategory())).count()!=3
                || questions.stream().filter(q -> "项目".equals(q.getCategory())).count()!=2
                || questions.stream().filter(q -> "行为".equals(q.getCategory())).count()!=1) throw invalid();
    }
    public void report(CandidateReportEntity report) {
        if (report == null || report.getFinalRecommend()==null || !Set.of("STRONG_YES","YES","NO").contains(report.getFinalRecommend())) throw invalid();
        text(report.getSummary(),2000); text(report.getReportMd(),60000);
        optional(report.getStrength(),10000); optional(report.getRisk(),10000); optional(report.getSalarySuggest(),100);
    }
    private void text(String value,int max) {
        if (value == null || value.isBlank()) throw invalid(); optional(value,max);
    }
    private void optional(String value,int max) {
        if (value != null && (value.length()>max || value.getBytes(StandardCharsets.UTF_8).length>60000)) throw invalid();
    }
    private MaterialFailure invalid() { return new MaterialFailure("INVALID_OUTPUT","生成内容不完整或格式不符合要求，原内容保留，请重新生成"); }
}
