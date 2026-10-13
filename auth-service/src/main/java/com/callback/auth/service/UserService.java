package com.callback.auth.service;

import com.callback.auth.model.RegisterRequest;
import com.callback.auth.model.RegisterResponse;
import com.callback.auth.model.User;
import com.callback.auth.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;
    // The SecurityConfig bean, the same one login's DaoAuthenticationProvider verifies with. A
    // private BCrypt instance would still verify (each hash carries its own salt and cost), but a
    // strength change would only reach one of the two, and swapping the bean's algorithm would
    // leave new registrations hashed with the old one.
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new EmailAlreadyRegisteredException(request.email());
        }

        User user = new User(request.email(), passwordEncoder.encode(request.password()));
        User saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two registrations for one email can both pass the check above; the unique constraint
            // on email rejects the second insert. Report that as the same 409, but only if the
            // email is really there now, so any other integrity failure still surfaces as itself.
            if (userRepository.findByEmail(request.email()).isPresent()) {
                throw new EmailAlreadyRegisteredException(request.email());
            }
            throw e;
        }

        return new RegisterResponse(saved.getId(), saved.getEmail(), saved.getCreatedAt());
    }
}
