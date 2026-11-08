package com.example.agent.service.materials;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agent.domain.recruitment.MaterialTaskResponse;
import com.example.agent.entity.CandidateReportEntity;
import com.example.agent.entity.InterviewQuestionEntity;
import com.example.agent.mapper.CandidateReportMapper;
import com.example.agent.mapper.InterviewQuestionMapper;
import com.example.agent.security.SecurityUtils;
import com.example.agent.service.InterviewService;
import com.example.agent.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class MaterialTaskService {
    private final MaterialTaskStore store;
    private final MaterialInputReader inputs;
    private final MaterialOutputValidator validator;
    private final InterviewService interviews;
    private final ReportService reports;
    private final InterviewQuestionMapper questions;
    private final CandidateReportMapper reportMapper;
    private final TransactionTemplate tx;
    private final int timeout;
    private final com.example.agent.service.company.PositionAccess access;
    private final com.example.agent.service.company.TaskAuthority authority;

    public MaterialTaskService(MaterialTaskStore store,MaterialInputReader inputs,MaterialOutputValidator validator,
            InterviewService interviews,ReportService reports,InterviewQuestionMapper questions,
            CandidateReportMapper reportMapper,TransactionTemplate tx,
            @Value("${material.tasks.timeout-seconds:600}") int timeout,
            com.example.agent.service.company.PositionAccess access,com.example.agent.service.company.TaskAuthority authority) {
        this.access=access;this.authority=authority;
        this.store=store; this.inputs=inputs; this.validator=validator; this.interviews=interviews; this.reports=reports;
        this.questions=questions; this.reportMapper=reportMapper; this.tx=tx; this.timeout=Math.max(1,timeout);
    }
    public MaterialTaskResponse submit(Long resumeId,String kind,String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,80}")) throw new IllegalArgumentException("请求标识无效");
        return view(create(resumeId,kind,key,false));
    }
    private MaterialTaskStore.Task create(Long resumeId,String kind,String key,boolean synchronous) {
        return tx.execute(status -> {
            var resume=access.resume(resumeId);
            var position=access.operator(resume.getPositionId(),access.user(),true);
            Long owner=position.getUserId();
            inputs.load(resumeId,owner,kind,false,true);
            store.expire();
            var prior=store.byKey(resumeId,kind,key);
            if (prior != null) return prior;
            var active=store.active(resumeId,kind);
            if (active != null) {
                if (synchronous) throw new MaterialFailure("TASK_ACTIVE","已有生成任务，请查看进度");
                store.remember(resumeId,kind,key,active.id()); return active;
            }
            var input=inputs.load(resumeId,owner,kind,true,true);
            String id=UUID.randomUUID().toString();
            store.create(id,resumeId,owner,kind,key,input.hash(),input.match()==null ? null : input.match().getMatchScore(),LocalDateTime.now().plusSeconds(timeout));
            authority.register("MATERIAL",id,position.getId(),owner);
            if (synchronous && !store.claim(id)) throw new IllegalStateException("Cannot start material task");
            return store.get(id);
        });
    }
    public void runSync(Long resumeId,String kind) {
        var task=create(resumeId,kind,UUID.randomUUID().toString(),true);
        execute(task.id(),false);
        var result=store.get(task.id());
        if (!"SUCCEEDED".equals(result.status())) throw new MaterialFailure(
                result.errorCode()==null ? "TASK_INACTIVE" : result.errorCode(),
                result.errorMessage()==null ? "生成任务已终止，原内容保留" : result.errorMessage());
    }
    public MaterialTaskResponse latest(Long resumeId,String kind) {
        var position=access.operator(access.resume(resumeId).getPositionId(),access.user(),false);
        inputs.load(resumeId,position.getUserId(),kind,false,false); store.expire();
        var task=store.latest(resumeId,kind); return task==null ? null : view(task);
    }
    public MaterialTaskResponse get(String id) {
        owned(id); store.expire(); return view(store.get(id));
    }
    public MaterialTaskResponse cancel(String id) {
        return tx.execute(status -> {
            var task=owned(id);Long positionId=access.resume(task.resumeId()).getPositionId();
            authority.cancelAllowed("MATERIAL",id,positionId,task.ownerId());
            if(List.of("QUEUED","RUNNING").contains(store.lock(id).status())) {
                store.terminate(id,"CANCELLED",null,"任务已取消，原内容保留");authority.log(positionId,access.user(),"MATERIAL_CANCELLED",id);
            }
            return get(id);
        });
    }
    private MaterialTaskStore.Task owned(String id) {
        var task=store.get(id);
        if (task==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        access.operator(access.resume(task.resumeId()).getPositionId(),access.user(),false);
        inputs.load(task.resumeId(),task.ownerId(),task.kind(),false,false); return task;
    }
    private MaterialTaskResponse view(MaterialTaskStore.Task task) {
        boolean stale="SUCCEEDED".equals(task.status()) && !inputs.load(task.resumeId(),task.ownerId(),task.kind(),false,false).hash().equals(task.hash());
        return new MaterialTaskResponse(task.id(),task.resumeId(),task.kind(),task.key(),task.status(),task.phase(),
                task.errorCode(),task.errorMessage(),task.deadline(),stale).authority(authority.actorId("MATERIAL",task.id(),task.ownerId()),authority.canCancel("MATERIAL",task.id(),task.ownerId()));
    }
    public void execute(String id,boolean claim) {
        if (claim && !store.claim(id)) return;
        try {
            var task=store.get(id); store.running(task);
            var input=inputs.load(task.resumeId(),task.ownerId(),task.kind(),true,false);
            authority.before("MATERIAL",id,input.position().getId(),task.ownerId());
            if (!input.hash().equals(task.hash())) throw changed();
            List<InterviewQuestionEntity> generatedQuestions;
            CandidateReportEntity generatedReport;
            if ("QUESTIONS".equals(task.kind())) {
                store.progress(id,"GENERATING");
                generatedQuestions=interviews.prepare(input.position(),input.resume(),input.match());
                validator.questions(generatedQuestions); generatedReport=null;
            } else {
                store.progress(id,"SEARCHING");
                generatedReport=reports.prepare(input.position(),input.resume(),input.match(),() -> { authority.before("MATERIAL",id,input.position().getId(),task.ownerId());store.progress(id,"GENERATING"); });
                validator.report(generatedReport); generatedQuestions=null;
            }
            store.progress(id,"SAVING");
            tx.executeWithoutResult(status -> {
                // Consistent lock order: position/resume/match, then task. No external calls here.
                var currentInput=inputs.load(task.resumeId(),task.ownerId(),task.kind(),true,true);
                authority.before("MATERIAL",id,currentInput.position().getId(),task.ownerId());
                store.running(store.lock(id));
                if (!currentInput.hash().equals(task.hash())) throw changed();
                Long resultId=null;
                if (generatedQuestions != null) {
                    questions.delete(new LambdaQueryWrapper<InterviewQuestionEntity>().eq(InterviewQuestionEntity::getResumeId,task.resumeId()));
                    for (var q:generatedQuestions) {
                        q.setResumeId(task.resumeId()); q.setPositionId(currentInput.position().getId());
                        q.setCreatedAt(LocalDateTime.now()); questions.insert(q);
                    }
                } else {
                    reportMapper.delete(new LambdaQueryWrapper<CandidateReportEntity>().eq(CandidateReportEntity::getResumeId,task.resumeId()));
                    generatedReport.setResumeId(task.resumeId()); generatedReport.setPositionId(currentInput.position().getId());
                    generatedReport.setHrDecision("PENDING"); generatedReport.setCreatedAt(LocalDateTime.now());
                    reportMapper.insert(generatedReport); resultId=generatedReport.getId();
                }
                store.complete(id,resultId);
            });
        } catch (com.example.agent.service.company.TaskAuthority.Revoked e) {
            store.terminate(id,"FAILED","AUTH_REVOKED",e.getMessage());
        } catch (MaterialFailure e) {
            store.expire(); store.terminate(id,"FAILED",e.code(),e.getMessage());
        } catch (Exception e) {
            log.warn("Material task failed: taskId={} type={}",id,e.getClass().getSimpleName());
            store.expire(); store.terminate(id,"FAILED","GENERATION_FAILED","生成失败，原内容保留，请检查服务后重试");
        }
    }
    private MaterialFailure changed() { return new MaterialFailure("INPUT_CHANGED","岗位、简历或匹配结果已变化，原内容保留，请重新生成"); }
    private Long owner() {
        Long owner=SecurityUtils.getCurrentUserId();
        if (owner==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); return owner;
    }
}
