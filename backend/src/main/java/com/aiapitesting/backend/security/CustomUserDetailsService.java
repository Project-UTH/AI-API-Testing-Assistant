package com.aiapitesting.backend.security;

import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Load lại từ DB MỖI request (JwtAuthFilter gọi hàm này, không đọc role/enabled từ payload
     * JWT) - cố ý, để việc cấp quyền ADMIN qua SQL trực tiếp hoặc khoá tài khoản có hiệu lực NGAY
     * ở request tiếp theo, không cần người dùng đăng xuất/đăng nhập lại để JWT mới mang role đúng.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy người dùng: " + email));

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPassword())
                .authorities("ROLE_" + user.getRole().name())
                .disabled(!user.isEnabled())
                .build();
    }
}
