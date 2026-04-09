package org.fabt.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Email delivery service. Only created when spring.mail.host is configured.
 * Uses plain text emails — no HTML template framework needed for MVP.
 * When SMTP is not configured, this bean does not exist — callers must null-check.
 */
@Service
@ConditionalOnProperty("spring.mail.host")
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@findabed.org}")
    private String fromAddress;

    @Value("${app.base-url:http://localhost:5173}")
    private String baseUrl;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Sends a password reset email with a tokenized link.
     * The link points to /login/reset-password?token={token} on the frontend.
     */
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        String resetUrl = baseUrl + "/login/reset-password?token=" + resetToken;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        // D6: Generic content — no platform name. Protects DV survivors whose email
        // may be monitored. "Finding A Bed Tonight" must NOT appear in subject or body.
        message.setSubject("Password Reset Request");
        message.setText(
                "You requested a password reset for your account.\n\n"
                + "Click the link below to set a new password (valid for 30 minutes):\n\n"
                + resetUrl + "\n\n"
                + "If you did not request this, you can safely ignore this email.");

        mailSender.send(message);
        log.info("Password reset email sent to {}", toEmail);
    }
}
