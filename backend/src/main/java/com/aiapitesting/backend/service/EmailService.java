package com.aiapitesting.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromAddress;

    public void sendPasswordResetOtp(String toEmail, String otpCode) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("Mã xác nhận đặt lại mật khẩu - AI API Testing Assistant");
        message.setText(
                "Mã xác nhận đặt lại mật khẩu của bạn là: " + otpCode + "\n\n"
                        + "Mã có hiệu lực trong 10 phút. Nếu bạn không yêu cầu đặt lại mật khẩu, vui lòng bỏ qua email này."
        );
        mailSender.send(message);
    }
}
