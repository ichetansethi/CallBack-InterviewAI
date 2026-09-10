package com.callback.question.client;

import com.callback.question.DTO.CompatibilityAnalysisResponse;
import com.callback.question.exception.UpstreamNotFoundException;
import com.callback.question.exception.UpstreamUnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Isolates whether the caller's token actually gets forwarded to compatibility-service, and
 * whether a rejected/missing token or an unowned analysis upstream fails cleanly (not silently,
 * not as a generic 500). No real compatibility-service involved — MockRestServiceServer
 * intercepts the RestClient at the HTTP layer.
 */
class CompatibilityClientTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final String TOKEN = "Bearer caller-jwt-value";

    private CompatibilityClient client(MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://compatibility-service");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        serverOut[0] = server;
        return new CompatibilityClient(builder.build());
    }

    @Test
    void forwardsTheCallersAuthorizationHeaderWhenFetchingAnAnalysis() {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        CompatibilityClient client = client(serverHolder);

        String body = """
                {
                  "id": "%s",
                  "jdId": "%s",
                  "resumeId": "%s",
                  "skillsOverlap": 80,
                  "experienceMatch": 70,
                  "keywordCoverage": 60,
                  "semanticSimilarity": 90,
                  "suggestions": [{"jdRequirement": "Kubernetes", "suggestion": "Add a K8s project"}],
                  "createdAt": "2026-01-01T00:00:00Z"
                }
                """.formatted(ANALYSIS_ID, UUID.randomUUID(), UUID.randomUUID());

        serverHolder[0].expect(requestTo("http://compatibility-service/compatibility/" + ANALYSIS_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", TOKEN))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        CompatibilityAnalysisResponse analysis = client.getAnalysis(ANALYSIS_ID, TOKEN);

        assertThat(analysis.id()).isEqualTo(ANALYSIS_ID);
        assertThat(analysis.suggestions()).hasSize(1);
        serverHolder[0].verify();
    }

    @Test
    void aMissingOrRejectedTokenUpstreamFailsCleanlyAs401NotA500() {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        CompatibilityClient client = client(serverHolder);

        serverHolder[0].expect(requestTo("http://compatibility-service/compatibility/" + ANALYSIS_ID))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getAnalysis(ANALYSIS_ID, "Bearer garbage-or-expired-token"))
                .isInstanceOf(UpstreamUnauthorizedException.class);
        serverHolder[0].verify();
    }

    @Test
    void anAnalysisOwnedBySomeoneElseComesBackAsNotFoundNotLeakedOr500() {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        CompatibilityClient client = client(serverHolder);

        serverHolder[0].expect(requestTo("http://compatibility-service/compatibility/" + ANALYSIS_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getAnalysis(ANALYSIS_ID, TOKEN))
                .isInstanceOf(UpstreamNotFoundException.class);
        serverHolder[0].verify();
    }
}
