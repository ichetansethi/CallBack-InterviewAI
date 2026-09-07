package com.callback.compatibility.client;

import com.callback.compatibility.exception.UpstreamNotFoundException;
import com.callback.compatibility.exception.UpstreamUnauthorizedException;
import org.junit.jupiter.api.Test;
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
 * Isolates the exact behavior the checklist calls out: does the caller's token actually get
 * forwarded to jd-resume-service, and does a rejected/missing token upstream fail cleanly (not
 * silently, not as a generic 500). No real jd-resume-service involved — MockRestServiceServer
 * intercepts the RestClient at the HTTP layer, so this verifies compatibility-service's own
 * client code in isolation from network/DB/LLM concerns.
 */
class JdResumeServiceClientTest {

    private static final UUID JD_ID = UUID.randomUUID();
    private static final UUID RESUME_ID = UUID.randomUUID();
    private static final String TOKEN = "Bearer caller-jwt-value";

    private JdResumeServiceClient client(MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://jd-resume-service");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        serverOut[0] = server;
        return new JdResumeServiceClient(builder.build());
    }

    @Test
    void forwardsTheCallersAuthorizationHeaderWhenFetchingJdText() {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        JdResumeServiceClient client = client(serverHolder);

        serverHolder[0].expect(requestTo("http://jd-resume-service/jd/" + JD_ID + "/text"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", TOKEN))
                .andRespond(withSuccess("Senior Backend Engineer JD text", MediaType.TEXT_PLAIN));

        String text = client.fetchJobDescriptionText(JD_ID, TOKEN);

        assertThat(text).isEqualTo("Senior Backend Engineer JD text");
        serverHolder[0].verify();
    }

    @Test
    void forwardsTheCallersAuthorizationHeaderWhenFetchingResumeText() {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        JdResumeServiceClient client = client(serverHolder);

        serverHolder[0].expect(requestTo("http://jd-resume-service/resumes/" + RESUME_ID + "/text"))
                .andExpect(header("Authorization", TOKEN))
                .andRespond(withSuccess("Jane Doe resume text", MediaType.TEXT_PLAIN));

        String text = client.fetchResumeText(RESUME_ID, TOKEN);

        assertThat(text).isEqualTo("Jane Doe resume text");
        serverHolder[0].verify();
    }

    @Test
    void aMissingOrRejectedTokenUpstreamFailsCleanlyAs401NotA500() {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        JdResumeServiceClient client = client(serverHolder);

        serverHolder[0].expect(requestTo("http://jd-resume-service/jd/" + JD_ID + "/text"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.fetchJobDescriptionText(JD_ID, "Bearer garbage-or-expired-token"))
                .isInstanceOf(UpstreamUnauthorizedException.class);
        serverHolder[0].verify();
    }

    @Test
    void aJdOwnedBySomeoneElseComesBackAsNotFoundNotLeakedOr500() {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        JdResumeServiceClient client = client(serverHolder);

        // jd-resume-service returns 404 for both "doesn't exist" and "not owned by this caller" —
        // this client must propagate that as-is, not mask it as a generic failure.
        serverHolder[0].expect(requestTo("http://jd-resume-service/jd/" + JD_ID + "/text"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.fetchJobDescriptionText(JD_ID, TOKEN))
                .isInstanceOf(UpstreamNotFoundException.class);
        serverHolder[0].verify();
    }

    @Test
    void aResumeOwnedBySomeoneElseComesBackAsNotFound() {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        JdResumeServiceClient client = client(serverHolder);

        serverHolder[0].expect(requestTo("http://jd-resume-service/resumes/" + RESUME_ID + "/text"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.fetchResumeText(RESUME_ID, TOKEN))
                .isInstanceOf(UpstreamNotFoundException.class);
        serverHolder[0].verify();
    }
}
