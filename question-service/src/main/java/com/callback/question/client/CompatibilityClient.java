package com.callback.question.client;

import com.callback.question.DTO.CompatibilityAnalysisResponse;
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
 * Calls compatibility-service on behalf of the current caller, forwarding their bearer token
 * as-is so compatibility-service's own ownership checks apply. Only invoked when the incoming
 * request carries a compatibilityAnalysisId — question generation can proceed on JD text alone
 * otherwise.
 */
@Component
public class CompatibilityClient {

    private final RestClient restClient;

    public CompatibilityClient(@Qualifier("compatibilityServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public CompatibilityAnalysisResponse getAnalysis(UUID analysisId, String bearerToken) {
        try {
            return restClient.get()
                    .uri("/compatibility/" + analysisId)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .body(CompatibilityAnalysisResponse.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new UpstreamNotFoundException("Compatibility analysis " + analysisId + " not found");
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new UpstreamUnauthorizedException("compatibility-service rejected the forwarded token");
        } catch (HttpClientErrorException e) {
            throw new UpstreamServiceException("compatibility-service returned " + e.getStatusCode() + " for /compatibility/" + analysisId, e);
        } catch (Exception e) {
            throw new UpstreamServiceException("Failed to reach compatibility-service for /compatibility/" + analysisId, e);
        }
    }
}
