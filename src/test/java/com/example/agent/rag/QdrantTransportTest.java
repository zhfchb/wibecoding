package com.example.agent.rag;

import com.example.agent.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.*;
import java.io.EOFException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class QdrantTransportTest {
    static final String BASE="http://qdrant.test";
    static final String COLLECTION=BASE+"/collections/knowledge";
    static final String POINTS=COLLECTION+"/points?wait=true";
    final String point=UUID.randomUUID().toString();
    MockRestServiceServer server;
    QdrantVectorStore store;
    @BeforeEach void setup() {
        var builder=RestClient.builder().baseUrl(BASE);server=MockRestServiceServer.bindTo(builder).build();
        store=new QdrantVectorStore(builder.build(),"knowledge",2);
    }
    ChunkRecord chunk() {return new ChunkRecord(point,"document","fixture","test.md",0,new float[]{1,0},null,7L);}
    void exists() {server.expect(requestTo(COLLECTION)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess());}
    void upsertSuccess() {server.expect(requestTo(POINTS)).andExpect(method(HttpMethod.PUT)).andExpect(jsonPath("$.points[0].id").value(point)).andRespond(withSuccess());}
    @AfterEach void verify(){server.verify();}
    @Test void existingCollectionIsCheckedOnceAndNeverRecreatedPerChunk() {
        exists();upsertSuccess();upsertSuccess();store.add(chunk());store.add(chunk());
    }
    @Test void missingCollectionIsCreatedBeforeFirstUpsert() {
        server.expect(requestTo(COLLECTION)).andExpect(method(HttpMethod.GET)).andRespond(withResourceNotFound());
        server.expect(requestTo(COLLECTION)).andExpect(method(HttpMethod.PUT)).andRespond(withSuccess());
        upsertSuccess();store.add(chunk());
    }
    @Test void lostUpsertResponseRetriesSamePointIdAndPayload() {
        exists();
        String expected="{\"points\":[{\"id\":\""+point+"\",\"vector\":{\"dense\":[1.0,0.0]},\"payload\":{\"text\":\"fixture\",\"document_id\":\"document\",\"file_name\":\"test.md\",\"chunk_index\":0,\"user_id\":7}}]}";
        server.expect(requestTo(POINTS)).andExpect(content().json(expected)).andRespond(request->{throw new EOFException("peer closed before response");});
        server.expect(requestTo(POINTS)).andExpect(content().json(expected)).andRespond(withSuccess());
        store.add(chunk());
    }
    @Test void persistentTransportFailureStopsAfterOneRetryAndMapsTo503() {
        exists();
        for(int i=0;i<2;i++) server.expect(requestTo(POINTS)).andRespond(request->{throw new EOFException("peer closed");});
        var error=catchThrowableOfType(()->store.add(chunk()),VectorStoreUnavailableException.class);
        assertThat(error.getCause()).isInstanceOf(ResourceAccessException.class);
        var response=new GlobalExceptionHandler().handleVectorStoreUnavailable(error);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().error()).isEqualTo("VECTOR_STORE_UNAVAILABLE");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
    }
    @Test void invalidRequestIsNotRetried() {
        exists();server.expect(requestTo(POINTS)).andRespond(withBadRequest());
        assertThatThrownBy(()->store.add(chunk())).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }
    @Test void collectionLookupTransportFailureDoesNotAttemptCreateOrUpsert() {
        for(int i=0;i<2;i++) server.expect(requestTo(COLLECTION)).andExpect(method(HttpMethod.GET)).andRespond(request->{throw new EOFException("peer closed");});
        assertThatThrownBy(()->store.add(chunk())).isInstanceOf(VectorStoreUnavailableException.class);
    }
    @Test void creationAcceptedButResponseLostAllowsConflictOnRetry() {
        server.expect(requestTo(COLLECTION)).andExpect(method(HttpMethod.GET)).andRespond(withResourceNotFound());
        server.expect(requestTo(COLLECTION)).andExpect(method(HttpMethod.PUT)).andRespond(request->{throw new EOFException("lost create response");});
        server.expect(requestTo(COLLECTION)).andExpect(method(HttpMethod.PUT)).andRespond(withStatus(HttpStatus.CONFLICT));
        upsertSuccess();store.add(chunk());
    }
    @Test void deletedCachedCollectionIsRecheckedOnce() {
        exists();upsertSuccess();
        server.expect(requestTo(POINTS)).andRespond(withResourceNotFound());
        server.expect(requestTo(COLLECTION)).andExpect(method(HttpMethod.GET)).andRespond(withResourceNotFound());
        server.expect(requestTo(COLLECTION)).andExpect(method(HttpMethod.PUT)).andRespond(withSuccess());
        upsertSuccess();store.add(chunk());store.add(chunk());
    }
    @Test void deletingAlreadyAbsentCollectionIsSuccessful() {
        server.expect(requestTo(COLLECTION+"/points/delete?wait=true")).andExpect(method(HttpMethod.POST)).andRespond(withResourceNotFound());
        store.deleteByDocumentId("document");
    }
    @Test void readOnlyQueryCanRetryWithoutRepeatingEmbedding() {
        exists();String url=COLLECTION+"/points/query";
        server.expect(requestTo(url)).andExpect(method(HttpMethod.POST)).andRespond(request->{throw new EOFException("lost query response");});
        server.expect(requestTo(url)).andExpect(method(HttpMethod.POST)).andRespond(withSuccess("{\"result\":{\"points\":[]}}",MediaType.APPLICATION_JSON));
        assertThat(store.search(new float[]{1,0},2,0,7L)).isEmpty();
    }
}
