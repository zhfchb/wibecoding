package com.example.agent.service;

import com.example.agent.rag.VectorMatch;
import com.example.agent.rag.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;

/** Durable upload leases and vector-delete outbox. SQL metadata controls retrieval visibility. */
@Service @Slf4j
public class DocumentLifecycle {
    private final JdbcTemplate db;
    private final VectorStore vectors;
    private final TransactionTemplate independent;
    private final boolean enabled;
    private final java.util.concurrent.atomic.AtomicBoolean draining = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService cleanupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(work -> {
        var thread=new Thread(work,"document-vector-cleanup");thread.setDaemon(true);return thread;
    });
    public DocumentLifecycle(JdbcTemplate db, VectorStore vectors, PlatformTransactionManager manager,
                             @Value("${knowledge.cleanup.enabled:true}") boolean enabled) {
        this.db=db;this.vectors=vectors;this.enabled=enabled;
        independent=new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void begin(String id) {
        independent.executeWithoutResult(s->db.update("INSERT INTO document_vector_cleanup(document_id,state,next_attempt,attempts) VALUES (?,'UPLOADING',?,0)",id,LocalDateTime.now().plusMinutes(15)));
    }
    public void heartbeat(String id) {
        independent.executeWithoutResult(s->{
            if(db.update("UPDATE document_vector_cleanup SET next_attempt=? WHERE document_id=? AND state='UPLOADING'",LocalDateTime.now().plusMinutes(15),id)!=1)
                throw new IllegalStateException("文档上传已失效，请重试");
        });
    }
    public void publish(String id,Runnable persist) {
        independent.executeWithoutResult(s->{
            var states=db.queryForList("SELECT state FROM document_vector_cleanup WHERE document_id=? FOR UPDATE",String.class,id);
            if(states.isEmpty() || !"UPLOADING".equals(states.get(0))) throw new IllegalStateException("文档上传已失效，请重试");
            persist.run();
            db.update("DELETE FROM document_vector_cleanup WHERE document_id=?",id);
        });
    }
    public void abandon(String id) { independent.executeWithoutResult(s->enqueue(id)); }
    private void enqueue(String id) {
        var rows=db.queryForList("SELECT document_id FROM document_vector_cleanup WHERE document_id=? FOR UPDATE",String.class,id);
        if(rows.isEmpty()) db.update("INSERT INTO document_vector_cleanup(document_id,state,next_attempt,attempts) VALUES (?,'PENDING',?,0)",id,LocalDateTime.now());
        else db.update("UPDATE document_vector_cleanup SET state='PENDING',next_attempt=?,lease_token=NULL WHERE document_id=?",LocalDateTime.now(),id);
    }
    @Transactional
    public void remove(String id) {
        db.update("UPDATE document SET deleted=1 WHERE document_id=?",id);
        db.update("DELETE FROM document_chunk WHERE document_id=?",id);
        enqueue(id);
    }
    /** Filter before reranking/returning text; outages fail closed instead of exposing stale vectors. */
    public List<VectorMatch> visible(List<VectorMatch> matches,Long owner) {
        if(matches.isEmpty()) return matches;
        var ids=matches.stream().map(VectorMatch::documentId).distinct().toList();
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var parameters=new ArrayList<Object>(ids);parameters.add(owner);
        var allowed=new HashSet<>(db.queryForList("SELECT document_id FROM document WHERE deleted=0 AND document_id IN ("+placeholders+") AND user_id=? AND NOT EXISTS (SELECT 1 FROM document_vector_cleanup c WHERE c.document_id=document.document_id)",String.class,parameters.toArray()));
        return matches.stream().filter(m->allowed.contains(m.documentId())).toList();
    }
    @Scheduled(fixedDelayString="${knowledge.cleanup.poll-ms:5000}")
    public void scheduledCleanup() {
        if(!enabled || !draining.compareAndSet(false,true)) return;
        cleanupExecutor.submit(()->{
            try { drain(); } catch(Exception e) { log.warn("Vector cleanup unavailable: {}",e.getClass().getSimpleName()); }
            finally { draining.set(false); }
        });
    }
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        cleanupExecutor.shutdownNow();
        try { cleanupExecutor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    public void drain() {
        var ids=db.queryForList("SELECT document_id FROM document_vector_cleanup WHERE next_attempt<=? ORDER BY next_attempt LIMIT 20",String.class,LocalDateTime.now());
        for(String id:ids) {
            String token=UUID.randomUUID().toString();
            int claimed=independent.execute(s->db.update("UPDATE document_vector_cleanup SET state='CLEANING',lease_token=?,next_attempt=? WHERE document_id=? AND next_attempt<=?",token,LocalDateTime.now().plusMinutes(5),id,LocalDateTime.now()));
            if(claimed==0) continue;
            try {
                vectors.deleteByDocumentId(id);
                independent.executeWithoutResult(s->db.update("DELETE FROM document_vector_cleanup WHERE document_id=? AND lease_token=? AND state='CLEANING'",id,token));
            } catch(Exception e) {
                independent.executeWithoutResult(s->db.update("UPDATE document_vector_cleanup SET state='PENDING',attempts=attempts+1,next_attempt=?,lease_token=NULL WHERE document_id=? AND lease_token=?",LocalDateTime.now().plusSeconds(60),id,token));
                log.warn("Vector cleanup will retry: documentId={} type={}",id,e.getClass().getSimpleName());
            }
        }
    }
}
