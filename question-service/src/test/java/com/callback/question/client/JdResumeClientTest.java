package com.callback.question.client;

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
 * Isolates whether the caller's token actually gets forwarded to jd-resume-service, and whether
 * a rejected/missing token or an unowned JD upstream fails cleanly (not silently, not as a
 * generic 500). No real jd-resume-service involved — MockRestServiceServer intercepts the
 * RestClient at the HTTP layer.
 */
class JdResumeClientTest {

    private static final UUID JD_ID = UUID.randomUUID();
    private static final String TOKEN = "Bearer caller-jwt-value";

    private JdResumeClient client(MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://jd-resume-service");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        serverOut[0] = server;
        return new JdResumeClient(builder.build());
    }

    @Test
    void forwardsTheCallersAuthorizationHeaderWhenFetchingJdText() {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        JdResumeClient client = client(serverHolder);

        serverHolder[0].expect(requestTo("http://jd-resume-service/jd/" + JD_ID + "/text"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", TOKEN))
                .andRespond(withSuccess("Senior Backend Engineer JD text", MediaType.TEXT_PLAIN));

        String text = client.getJdText(JD_ID, TOKEN);

        assertThat(text).isEqualTo("Senior Backend Engineer JD text");
        serverHolder[0].verify();
    }

    @Test
    void aMissingOrRejectedTokenUpstreamFailsCleanlyAs401NotA500() {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        JdResumeClient client = client(serverHolder);

        serverHolder[0].expect(requestTo("http://jd-resume-service/jd/" + JD_ID + "/text"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getJdText(JD_ID, "Bearer garbage-or-expired-token"))
                .isInstanceOf(UpstreamUnauthorizedException.class);
        serverHolder[0].verify();
    }

    @Test
    void aJdOwnedBySomeoneElseComesBackAsNotFoundNotLeakedOr500() {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        JdResumeClient client = client(serverHolder);

        // jd-resume-service returns 404 for both "doesn't exist" and "not owned by this caller" —
        // this client must propagate that as-is, not mask it as a generic failure.
        serverHolder[0].expect(requestTo("http://jd-resume-service/jd/" + JD_ID + "/text"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getJdText(JD_ID, TOKEN))
                .isInstanceOf(UpstreamNotFoundException.class);
        serverHolder[0].verify();
    }
}
