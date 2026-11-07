package com.example.agent.service.resumes;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.agent.domain.recruitment.ResumeParseTaskResponse;
import com.example.agent.entity.CandidateResumeEntity;
import com.example.agent.mapper.CandidateResumeMapper;
import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.security.SecurityUtils;
import com.example.agent.service.ResumeParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class ResumeParseTaskService {
    private final ResumeParseTaskStore store;
    private final JobPositionMapper positions;
    private final CandidateResumeMapper resumes;
    private final ResumeParser parser;
    private final ResumeProfileReader profiles;
    private final TransactionTemplate tx;
    private final int timeout;

    public ResumeParseTaskService(ResumeParseTaskStore store, JobPositionMapper positions,
                                  CandidateResumeMapper resumes, ResumeParser parser, ResumeProfileReader profiles,
                                  TransactionTemplate tx, @Value("${resume.parse-tasks.timeout-seconds:600}") int timeout) {
        this.store=store;this.positions=positions;this.resumes=resumes;this.parser=parser;
        this.profiles=profiles;this.tx=tx;this.timeout=Math.max(1,timeout);
    }

    public ResumeParseTaskResponse submit(Long positionId, String key, List<Long> ids) {
        if (key==null || !key.matches("[A-Za-z0-9_-]{8,80}") || ids==null || ids.isEmpty() || ids.size()>20
                || ids.stream().anyMatch(i -> i==null || i<=0) || ids.stream().distinct().count()!=ids.size()) {
            throw new IllegalArgumentException("请求标识无效，或未选择 1～20 份不同的失败简历");
        }
        Long owner=owner();
        String hash=hash(ids.stream().sorted().toList().toString());
        return tx.execute(status -> {
            requirePosition(positionId,owner,true);
            expirePosition(positionId);
            var prior=store.byKey(positionId,key);
            if (prior!=null) {
                if (!hash.equals(prior.inputHash())) throw failure("REQUEST_CONFLICT","同一请求标识不能用于不同简历，请刷新后重试");
                return store.view(prior);
            }
            if (store.active(positionId)!=null) throw failure("TASK_ACTIVE","该岗位已有重新解析任务，请查看进度");
            var selected=ids.stream().sorted().map(id -> eligible(id,positionId,owner)).toList();
            String id=UUID.randomUUID().toString();
            store.create(id,positionId,owner,key,hash,LocalDateTime.now().plusSeconds(timeout));
            for (int i=0;i<selected.size();i++) {
                var resume=selected.get(i);
                store.addItem(id,resume.getId(),i,hash(resume.getRawText()));
            }
            return store.view(store.get(id));
        });
    }

    public ResumeParseTaskResponse latest(Long positionId) {
        requirePosition(positionId,owner(),false);
        expire();
        var task=store.latest(positionId);return task==null ? null : store.view(task);
    }
    public ResumeParseTaskResponse get(String id) {
        owned(id);expire();return store.view(store.get(id));
    }
    public ResumeParseTaskResponse cancel(String id) {
        var task=owned(id);
        return tx.execute(status -> {
            store.lock(id);
            store.terminate(id,"CANCELLED",null,"任务已取消，已完成的解析结果保留");
            return store.view(store.get(task.id()));
        });
    }

    public void execute(String id) {
        if (!store.claim(id)) return;
        var task=store.get(id);
        for (var item:store.items(id)) {
            if (!List.of("QUEUED","RUNNING").contains(item.status())) continue;
            try {
                var text=tx.execute(status -> {
                    requirePosition(task.positionId(),task.ownerId(),true);
                    running(store.lock(id));
                    var resume=eligible(item.resumeId(),task.positionId(),task.ownerId());
                    if (!hash(resume.getRawText()).equals(item.inputHash())) throw failure("INPUT_CHANGED","简历原文已变化，请重新提交");
                    store.itemStatus(id,item.resumeId(),"RUNNING",null,null);
                    return resume.getRawText();
                });
                // No transaction or inherited Web identity during the paid external request.
                CandidateResumeEntity profile;
                try { profile=profiles.read(parser.parse(text)); }
                catch (ResumeParseFailure e) { throw e; }
                catch (Exception e) { throw failure("PARSER_UNAVAILABLE","解析服务暂不可用，请检查服务后重新解析"); }
                tx.executeWithoutResult(status -> {
                    requirePosition(task.positionId(),task.ownerId(),true);
                    running(store.lock(id));
                    var resume=eligible(item.resumeId(),task.positionId(),task.ownerId());
                    if (!hash(resume.getRawText()).equals(item.inputHash())) throw failure("INPUT_CHANGED","简历原文已变化，未覆盖现有结果");
                    resumes.update(null,new LambdaUpdateWrapper<CandidateResumeEntity>()
                            .eq(CandidateResumeEntity::getId,item.resumeId())
                            .set(CandidateResumeEntity::getCandidateName,profile.getCandidateName())
                            .set(CandidateResumeEntity::getEmail,profile.getEmail())
                            .set(CandidateResumeEntity::getEducation,profile.getEducation())
                            .set(CandidateResumeEntity::getLastCompany,profile.getLastCompany())
                            .set(CandidateResumeEntity::getYearsExp,profile.getYearsExp())
                            .set(CandidateResumeEntity::getSkills,profile.getSkills())
                            .set(CandidateResumeEntity::getParseStatus,"PARSED")
                            .set(CandidateResumeEntity::getParseError,null));
                    running(store.lock(id));
                    store.itemStatus(id,item.resumeId(),"SUCCEEDED",null,null);
                });
            } catch (Exception e) {
                String code=e instanceof ResumeParseFailure f ? f.code() : "EXECUTION_FAILED";
                String message=e instanceof ResumeParseFailure ? e.getMessage() : "重新解析执行失败，原结果保留，请检查服务后重试";
                log.warn("Resume retry failed: taskId={} resumeId={} type={}",id,item.resumeId(),e.getClass().getSimpleName());
                tx.executeWithoutResult(status -> {
                    var current=store.lock(id);
                    if (!active(current)) return;
                    if (!current.deadline().isAfter(LocalDateTime.now())) {
                        store.terminate(id,"FAILED","TIMEOUT","任务超时或进程中断，已完成结果保留，请确认后重试");
                    } else if (Thread.currentThread().isInterrupted()) {
                        store.terminate(id,"FAILED","INTERRUPTED","执行已中断，已完成结果保留，请确认后重试");
                    } else {
                        store.itemStatus(id,item.resumeId(),"FAILED",code,message);
                    }
                });
            }
            var current=store.get(id);
            if (!active(current)) return;
        }
        tx.executeWithoutResult(status -> {
            var current=store.lock(id);
            if (!active(current)) return;
            if (current.deadline().isAfter(LocalDateTime.now())) store.finish(id);
            else store.terminate(id,"FAILED","TIMEOUT","任务超时，已完成结果保留，请确认后重试");
        });
    }

    public void expire() {
        for (var task:store.expired()) tx.executeWithoutResult(status -> {
            var current=store.lock(task.id());
            if (active(current) && !current.deadline().isAfter(LocalDateTime.now()))
                store.terminate(current.id(),"FAILED","TIMEOUT","任务超时或进程中断，已完成结果保留，请确认后重试");
        });
    }
    private void expirePosition(Long positionId) {
        var task=store.active(positionId);
        if (task!=null && !task.deadline().isAfter(LocalDateTime.now())) {
            store.lock(task.id());store.terminate(task.id(),"FAILED","TIMEOUT","任务超时，请确认后重试");
        }
    }
    private CandidateResumeEntity eligible(Long id,Long positionId,Long owner) {
        var resume=resumes.selectOne(new LambdaQueryWrapper<CandidateResumeEntity>()
                .eq(CandidateResumeEntity::getId,id).last("FOR UPDATE"));
        if (resume==null || !positionId.equals(resume.getPositionId()) || !owner.equals(resume.getUserId()))
            throw failure("RESUME_UNAVAILABLE","简历不存在或不属于该岗位");
        if (!"FAILED".equals(resume.getParseStatus()) || !StringUtils.hasText(resume.getRawText()))
            throw failure("NOT_RETRYABLE","仅支持重新解析已保存原文的失败简历，请刷新列表或重新上传");
        return resume;
    }
    private void requirePosition(Long id,Long owner,boolean lock) {
        var position=lock ? positions.lockById(id) : positions.selectById(id);
        if (position==null || !owner.equals(position.getUserId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (lock && "CLOSED".equals(position.getStatus())) throw failure("POSITION_CLOSED","岗位已关闭，不能重新解析");
    }
    private ResumeParseTaskStore.Task owned(String id) {
        var task=store.get(id);
        if (task==null || !owner().equals(task.ownerId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        requirePosition(task.positionId(),task.ownerId(),false);return task;
    }
    private Long owner() {
        var owner=SecurityUtils.getCurrentUserId();
        if (owner==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return owner;
    }
    private boolean active(ResumeParseTaskStore.Task task) { return task!=null && List.of("QUEUED","RUNNING").contains(task.status()); }
    private void running(ResumeParseTaskStore.Task task) {
        if (Thread.currentThread().isInterrupted()) throw failure("INTERRUPTED","执行线程已中断");
        if (task==null || !"RUNNING".equals(task.status()) || !task.deadline().isAfter(LocalDateTime.now()))
            throw failure("TASK_INACTIVE","任务已终止或超时，未覆盖解析结果");
    }
    private String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Cannot hash resume input",e); }
    }
    private ResumeParseFailure failure(String code,String message) { return new ResumeParseFailure(code,message); }
}
