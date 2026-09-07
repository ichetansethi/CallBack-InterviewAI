package com.callback.compatibility.client;

import com.callback.compatibility.exception.UpstreamNotFoundException;
import com.callback.compatibility.exception.UpstreamServiceException;
import com.callback.compatibility.exception.UpstreamUnauthorizedException;
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
public class JdResumeServiceClient {

    private final RestClient restClient;

    public JdResumeServiceClient(RestClient jdResumeServiceRestClient) {
        this.restClient = jdResumeServiceRestClient;
    }

    public String fetchJobDescriptionText(UUID jdId, String authorizationHeader) {
        return fetchText("/jd/" + jdId + "/text", authorizationHeader, "Job description " + jdId);
    }

    public String fetchResumeText(UUID resumeId, String authorizationHeader) {
        return fetchText("/resumes/" + resumeId + "/text", authorizationHeader, "Resume " + resumeId);
    }

    private String fetchText(String path, String authorizationHeader, String subjectDescription) {
        try {
            return restClient.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new UpstreamNotFoundException(subjectDescription + " not found");
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new UpstreamUnauthorizedException("jd-resume-service rejected the forwarded token");
        } catch (HttpClientErrorException e) {
            throw new UpstreamServiceException("jd-resume-service returned " + e.getStatusCode() + " for " + path, e);
        } catch (Exception e) {
            throw new UpstreamServiceException("Failed to reach jd-resume-service for " + path, e);
        }
    }
}
