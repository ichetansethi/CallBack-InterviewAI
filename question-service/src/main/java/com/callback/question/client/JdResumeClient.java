package com.callback.question.client;

import com.callback.question.exception.UpstreamNotFoundException;
import com.callback.question.exception.UpstreamServiceException;
import com.callback.question.exception.UpstreamUnauthorizedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Calls jd-resume-service on behalf of the current caller, forwarding their bearer token as-is
 * so jd-resume-service's own ownership checks apply. This service never re-derives or re-checks
 * ownership itself — it only relays the caller's identity and translates upstream failures into
 * the equivalent local ones (jd-resume-service's 404-for-not-owned becomes ours too).
 */
@Component
public class JdResumeClient {

    private final RestClient restClient;

    public JdResumeClient(@Qualifier("jdResumeServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public String getJdText(UUID jdId, String bearerToken) {
        try {
            return restClient.get()
                    .uri("/jd/" + jdId + "/text")
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new UpstreamNotFoundException("Job description " + jdId + " not found");
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new UpstreamUnauthorizedException("jd-resume-service rejected the forwarded token");
        } catch (HttpClientErrorException e) {
            throw new UpstreamServiceException("jd-resume-service returned " + e.getStatusCode() + " for /jd/" + jdId + "/text", e);
        } catch (Exception e) {
            throw new UpstreamServiceException("Failed to reach jd-resume-service for /jd/" + jdId + "/text", e);
        }
    }
}
