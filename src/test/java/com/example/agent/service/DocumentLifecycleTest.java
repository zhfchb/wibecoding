package com.example.agent.service;

import com.example.agent.entity.*;
import com.example.agent.mapper.*;
import com.example.agent.rag.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:document_lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class DocumentLifecycleTest {
    @Autowired KnowledgeService knowledge;
    @SpyBean DocumentLifecycle lifecycle;
    @Autowired RecruitmentService recruitment;
    @Autowired com.example.agent.service.company.RecruitmentOutcomeService outcomes;
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockBean VectorStore vectors;
    @MockBean AiGateway ai;
    @SpyBean DocumentMapper documents;
    @SpyBean DocumentChunkMapper chunks;
    @BeforeEach void setup() {
        var identity=new UsernamePasswordAuthenticationToken("fixture",null,List.of());identity.setDetails(Map.of("userId",901L));SecurityContextHolder.getContext().setAuthentication(identity);
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();db.update("DELETE FROM document_vector_cleanup");db.update("DELETE FROM document_chunk");db.update("DELETE FROM document");
    }
    MockMultipartFile file() {return new MockMultipartFile("file","consistency.md","text/markdown","Java Spring Boot reliability fixture".getBytes());}
    List<VectorMatch> matches(String id) {return List.of(new VectorMatch("chunk",id,"Java","consistency.md",0,1));}
    String pending() {return db.queryForObject("SELECT document_id FROM document_vector_cleanup",String.class);}
    void due() {db.update("UPDATE document_vector_cleanup SET next_attempt=DATEADD('MINUTE',-20,CURRENT_TIMESTAMP)");}
    @Test void metadataFailureIsNotSuccessAndSqlChunksRollback() {
        doThrow(new IllegalStateException("DB insert failure")).when(documents).insert(any(DocumentEntity.class));
        assertThatThrownBy(()->knowledge.upload(file())).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_chunk",Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document",Long.class)).isZero();
        String id=pending();assertThat(lifecycle.visible(matches(id),901L)).isEmpty();
        due();lifecycle.drain();verify(vectors).deleteByDocumentId(id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_vector_cleanup",Long.class)).isZero();
    }
    @Test void chunkFailureRollsBackDocumentAndQueuesCleanup() {
        doThrow(new IllegalStateException("DB chunk failure")).when(chunks).insert(any(DocumentChunkEntity.class));
        assertThatThrownBy(()->knowledge.upload(file())).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document",Long.class)).isZero();assertThat(pending()).isNotBlank();
    }
    @Test void uncertainVectorWriteQueuesCompensation() {
        doThrow(new IllegalStateException("timeout after remote accepted write")).when(vectors).add(any());
        assertThatThrownBy(()->knowledge.upload(file())).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document",Long.class)).isZero();
        String id=pending();
        assertThat(db.queryForObject("SELECT state FROM document_vector_cleanup WHERE document_id=?",String.class,id)).isEqualTo("PENDING");
        due();lifecycle.drain();verify(vectors).deleteByDocumentId(id);
    }
    @Test void deletionIsImmediatelyHiddenAndPhysicalFailureRetries() {
        String id=knowledge.upload(file()).documentId();assertThat(lifecycle.visible(matches(id),901L)).hasSize(1);
        knowledge.deleteDocument(id,901L);
        assertThat(lifecycle.visible(matches(id),901L)).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE document_id=?",Long.class,id)).isZero();
        doThrow(new IllegalStateException("Qdrant offline")).when(vectors).deleteByDocumentId(id);
        due();lifecycle.drain();assertThat(db.queryForObject("SELECT attempts FROM document_vector_cleanup WHERE document_id=?",Integer.class,id)).isEqualTo(1);
        doNothing().when(vectors).deleteByDocumentId(id);due();lifecycle.drain();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_vector_cleanup",Long.class)).isZero();
    }
    @Test void sqlRollbackKeepsDocumentVisibleAndDoesNotQueueDeletion() {
        String id=knowledge.upload(file()).documentId();
        assertThatThrownBy(()->tx.executeWithoutResult(status->{knowledge.deleteDocument(id,901L);throw new IllegalStateException("outer delete failed");})).isInstanceOf(IllegalStateException.class);
        assertThat(lifecycle.visible(matches(id),901L)).hasSize(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_vector_cleanup",Long.class)).isZero();
    }
    @Test void absentWrongOwnerAndDeletedMetadataAreFiltered() {
        String id=knowledge.upload(file()).documentId();assertThat(lifecycle.visible(matches(id),902L)).isEmpty();
        assertThat(lifecycle.visible(matches("orphan"),901L)).isEmpty();
        knowledge.deleteDocument(id,901L);assertThat(lifecycle.visible(matches(id),901L)).isEmpty();
    }
    @Test void abandonedUploadLeaseIsRecoveredButLiveUploadIsProtected() {
        lifecycle.begin("lease");lifecycle.drain();verifyNoInteractions(vectors);
        due();lifecycle.drain();verify(vectors).deleteByDocumentId("lease");
        assertThatThrownBy(()->lifecycle.publish("lease",()->{})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->lifecycle.heartbeat("lease")).isInstanceOf(IllegalStateException.class);
    }
    @Test void failedPublicationCallbackRollsBackAllSqlAndQueuesVectors() {
        assertThatThrownBy(()->knowledge.upload(file(),id->{db.update("INSERT INTO document(document_id,file_name,chunk_count,file_size,user_id) VALUES (?,'callback',0,0,901)",id+"-callback");throw new IllegalStateException("job deleted during parsing");})).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document",Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_chunk",Long.class)).isZero();assertThat(pending()).isNotBlank();
    }
    @Test void wrongOwnerCannotScheduleDeletion() {
        String id=knowledge.upload(file()).documentId();assertThatThrownBy(()->knowledge.deleteDocument(id,902L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(lifecycle.visible(matches(id),901L)).hasSize(1);assertThat(db.queryForObject("SELECT COUNT(*) FROM document_vector_cleanup",Long.class)).isZero();
    }
    @Test void cleanupSurvivesServiceRestartAndExpiredWorkerLease() {
        String id=knowledge.upload(file()).documentId();knowledge.deleteDocument(id,901L);
        db.update("UPDATE document_vector_cleanup SET state='CLEANING',lease_token='dead-worker'");due();
        new DocumentLifecycle(db,vectors,manager,false).drain();
        verify(vectors).deleteByDocumentId(id);assertThat(db.queryForObject("SELECT COUNT(*) FROM document_vector_cleanup",Long.class)).isZero();
    }
    @Test void slowVectorCleanupDoesNotBlockSharedSchedulerOrCreateUnboundedWork() throws Exception {
        lifecycle.abandon("slow-cleanup");due();
        var started=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call->{started.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return null;}).when(vectors).deleteByDocumentId("slow-cleanup");
        var worker=new DocumentLifecycle(db,vectors,manager,true);
        try {
            worker.scheduledCleanup();
            assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            worker.scheduledCleanup();verify(vectors,times(1)).deleteByDocumentId("slow-cleanup");
        } finally { release.countDown();worker.shutdown(); }
    }
    @Test void documentSqlFailureRollsBackWholeResumeDeletion() {
        String id=knowledge.upload(file()).documentId();
        db.update("INSERT INTO job_position(user_id,title,status) VALUES (901,'delete consistency','OPEN')");
        long job=db.queryForObject("SELECT MAX(id) FROM job_position",Long.class);
        db.update("INSERT INTO candidate_resume(position_id,user_id,doc_id,parse_status) VALUES (?,901,?,'PARSED')",job,id);
        long resume=db.queryForObject("SELECT MAX(id) FROM candidate_resume",Long.class);
        try {
            outcomes.change(resume,new com.example.agent.service.company.RecruitmentOutcomeService.Input(0,"DECIDE","HIRED","fixture"));
            doThrow(new IllegalStateException("outbox SQL failed")).when(lifecycle).remove(id);
            assertThatThrownBy(()->recruitment.deleteResume(resume)).isInstanceOf(IllegalStateException.class);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_resume WHERE id=?",Long.class,resume)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_candidate_outcome WHERE resume_id=?",Long.class,resume)).isEqualTo(1);
            assertThat(lifecycle.visible(matches(id),901L)).hasSize(1);
        } finally {
            outcomes.cleanup(resume);db.update("DELETE FROM recruitment_position_activity WHERE position_id=?",job);
            db.update("DELETE FROM candidate_resume WHERE id=?",resume);db.update("DELETE FROM job_position WHERE id=?",job);
        }
    }
}
