package com.agrosense.backend.security;

import com.agrosense.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        return userRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .map(user -> User.withUsername(user.getEmail())
                        .password(user.getPasswordHash())
                        .authorities("ROLE_" + user.getRole().toUpperCase(Locale.ROOT))
                        .disabled(!Boolean.TRUE.equals(user.getActive()))
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}
