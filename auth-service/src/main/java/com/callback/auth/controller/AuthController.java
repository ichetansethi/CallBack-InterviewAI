package com.callback.auth.controller;

import com.callback.auth.model.LoginRequest;
import com.callback.auth.model.LoginResponse;
import com.callback.auth.model.RegisterRequest;
import com.callback.auth.model.RegisterResponse;
import com.callback.auth.service.JwtIssuerService;
import com.callback.auth.service.UserService;
import com.callback.security.jwt.JwtValidator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final JwtIssuerService jwtIssuerService;
    private final JwtValidator jwtValidator;

    public AuthController(UserService userService, AuthenticationManager authenticationManager,
                           JwtIssuerService jwtIssuerService, JwtValidator jwtValidator) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.jwtIssuerService = jwtIssuerService;
        this.jwtValidator = jwtValidator;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        String token = jwtIssuerService.generateToken(request.email());
        return ResponseEntity.ok(new LoginResponse(token, jwtValidator.extractExpiration(token)));
    }

    @GetMapping("/me")
    public ResponseEntity<String> me(Authentication authentication) {
        return ResponseEntity.ok(authentication.getName());
    }
}
