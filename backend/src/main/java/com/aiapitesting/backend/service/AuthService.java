package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.ChangePasswordRequest;
import com.aiapitesting.backend.dto.request.ForgotPasswordRequest;
import com.aiapitesting.backend.dto.request.LoginRequest;
import com.aiapitesting.backend.dto.request.RegisterRequest;
import com.aiapitesting.backend.dto.request.ResetPasswordRequest;
import com.aiapitesting.backend.dto.response.AuthResponse;
import com.aiapitesting.backend.entity.PasswordResetOtp;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.AccountDisabledException;
import com.aiapitesting.backend.exception.EmailAlreadyExistsException;
import com.aiapitesting.backend.exception.InvalidCredentialsException;
import com.aiapitesting.backend.exception.InvalidCurrentPasswordException;
import com.aiapitesting.backend.exception.InvalidResetCodeException;
import com.aiapitesting.backend.repository.PasswordResetOtpRepository;
import com.aiapitesting.backend.repository.UserRepository;
import com.aiapitesting.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthService {

    // Sai/hết hạn mã đều trả CHUNG 1 thông báo - không cho kẻ tấn công phân biệt được "email không
    // tồn tại" / "email tồn tại nhưng mã sai" / "mã hết hạn", tránh dò email đã đăng ký.
    private static final String GENERIC_RESET_CODE_ERROR = "Mã xác nhận không đúng hoặc đã hết hạn";
    private static final Duration OTP_VALIDITY = Duration.ofMinutes(10);
    private static final int MAX_OTP_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PasswordResetOtpRepository passwordResetOtpRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException("Email đã được đăng ký");
        }

        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .build();
        userRepository.save(user);

        String token = jwtService.generateToken(user.getEmail());
        return new AuthResponse(token, user.getEmail(), user.getRole());
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCredentialsException("Sai email hoặc mật khẩu"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException("Sai email hoặc mật khẩu");
        }
        // Chặn ngay lúc đăng nhập (khác JwtAuthFilter chặn request của token ĐÃ phát hành trước đó) -
        // tài khoản bị khoá không nên nhận được token mới.
        if (!user.isEnabled()) {
            throw new AccountDisabledException("Tài khoản của bạn đã bị khoá, liên hệ quản trị viên");
        }

        String token = jwtService.generateToken(user.getEmail());
        return new AuthResponse(token, user.getEmail(), user.getRole());
    }

    public void changePassword(User currentUser, ChangePasswordRequest request) {
        if (!passwordEncoder.matches(request.currentPassword(), currentUser.getPassword())) {
            throw new InvalidCurrentPasswordException("Mật khẩu hiện tại không đúng");
        }

        currentUser.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(currentUser);
    }

    // Luôn "thành công" ở tầng Controller bất kể email có tồn tại hay không (xem AuthController) -
    // no-op nếu không tìm thấy user, tránh lộ email nào đã đăng ký qua timing/response khác biệt.
    // @Transactional bắt buộc - deleteAllUnusedByUser là bulk @Modifying query, cần transaction chủ
    // động (khác save()/findBy... của Spring Data tự có transaction riêng theo từng method).
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(request.email()).ifPresent(user -> {
            // Chỉ 1 mã hợp lệ tại 1 thời điểm cho mỗi user - mã cũ (nếu có) bị vô hiệu ngay.
            passwordResetOtpRepository.deleteAllUnusedByUser(user);

            String otpCode = generateOtpCode();
            PasswordResetOtp otp = PasswordResetOtp.builder()
                    .user(user)
                    .otpHash(passwordEncoder.encode(otpCode))
                    .expiresAt(Instant.now().plus(OTP_VALIDITY))
                    .build();
            passwordResetOtpRepository.save(otp);

            emailService.sendPasswordResetOtp(user.getEmail(), otpCode);
        });
    }

    // noRollbackFor bat buoc: nhanh "sai OTP" luu attemptCount tang len ROI MOI nem exception -
    // @Transactional mac dinh rollback toan bo giao dich khi co RuntimeException thoat ra ngoai,
    // se xoa mat luon attemptCount vua tang (phat hien duoc qua Playwright/curl that: brute-force
    // 5 lan sai OTP van khong bi khoa vi counter luon bi rollback ve 0).
    @Transactional(noRollbackFor = InvalidResetCodeException.class)
    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidResetCodeException(GENERIC_RESET_CODE_ERROR));

        PasswordResetOtp otp = passwordResetOtpRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user)
                .orElseThrow(() -> new InvalidResetCodeException(GENERIC_RESET_CODE_ERROR));

        if (otp.getExpiresAt().isBefore(Instant.now()) || otp.getAttemptCount() >= MAX_OTP_ATTEMPTS) {
            throw new InvalidResetCodeException(GENERIC_RESET_CODE_ERROR);
        }

        if (!passwordEncoder.matches(request.otp(), otp.getOtpHash())) {
            otp.setAttemptCount(otp.getAttemptCount() + 1);
            passwordResetOtpRepository.save(otp);
            throw new InvalidResetCodeException(GENERIC_RESET_CODE_ERROR);
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        otp.setUsed(true);
        passwordResetOtpRepository.save(otp);
    }

    private String generateOtpCode() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }
}
