package com.callback.voice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Safe to scan com.callback broadly, like every sibling service: JwtAuthenticationFilter (servlet)
// and ReactiveJwtAuthenticationFilter are each guarded by @ConditionalOnWebApplication, so only
// the one matching this app's actual (reactive) stack is ever instantiated. See
// ReactiveJwtAuthenticationFilter's Javadoc in common-security for why that's safe even before
// classloading is attempted, not just "happens to work."
@SpringBootApplication(scanBasePackages = "com.callback")
public class VoiceOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoiceOrchestratorApplication.class, args);
    }

}
