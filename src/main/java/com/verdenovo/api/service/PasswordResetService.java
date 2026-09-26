package com.verdenovo.api.service;

import com.verdenovo.api.entity.Usuario;
import com.verdenovo.api.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;


@Service
public class PasswordResetService {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${spring.mail.username}")
    private String emailRemetente;

    private final java.security.SecureRandom secureRandom = new java.security.SecureRandom();

    private String gerarCodigo() {
        return String.format("%06d", secureRandom.nextInt(999999));
    }

    public void solicitarRecuperacao(String email) {
        Usuario usuario = usuarioRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Email não encontrado"));

        String codigo = gerarCodigo();
        usuario.setResetCode(codigo);
        usuario.setResetToken(null);
        usuario.setResetTokenExpiry(LocalDateTime.now().plusMinutes(15));
        usuarioRepository.save(usuario);

        enviarEmailCodigo(email, usuario.getNome(), codigo);
    }

    public void verificarCodigo(String email, String codigo) {
        Usuario usuario = usuarioRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Email não encontrado"));

        if (usuario.getResetCode() == null || !usuario.getResetCode().equals(codigo)) {
            throw new RuntimeException("Código inválido.");
        }

        if (usuario.getResetTokenExpiry() == null ||
            usuario.getResetTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Código expirado. Solicite um novo.");
        }
    }

    public void redefinirSenhaPorCodigo(String email, String codigo, String novaSenha) {
        Usuario usuario = usuarioRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Email não encontrado"));

        if (usuario.getResetCode() == null || !usuario.getResetCode().equals(codigo)) {
            throw new RuntimeException("Código inválido.");
        }

        if (usuario.getResetTokenExpiry() == null ||
            usuario.getResetTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Código expirado. Solicite um novo.");
        }

        usuario.setSenha(passwordEncoder.encode(novaSenha));
        usuario.setResetCode(null);
        usuario.setResetToken(null);
        usuario.setResetTokenExpiry(null);
        usuarioRepository.save(usuario);
    }

    // Mantido para compatibilidade com fluxo antigo de pontos
    public void redefinirSenha(String token, String novaSenha) {
        Usuario usuario = usuarioRepository.findByResetToken(token)
            .orElseThrow(() -> new RuntimeException("Token inválido"));

        if (usuario.getResetTokenExpiry() == null ||
            usuario.getResetTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Token expirado. Solicite uma nova recuperação.");
        }

        usuario.setSenha(passwordEncoder.encode(novaSenha));
        usuario.setResetToken(null);
        usuario.setResetTokenExpiry(null);
        usuarioRepository.save(usuario);
    }

    private void enviarEmailCodigo(String destinatario, String nome, String codigo) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(emailRemetente);
            helper.setTo(destinatario);
            helper.setSubject("VerDenovo - Código de Recuperação de Senha");

            String html = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                  <div style="background: linear-gradient(135deg, #10b981, #059669); padding: 2rem; text-align: center; border-radius: 12px 12px 0 0;">
                    <h1 style="color: white; margin: 0;">VerDenovo</h1>
                  </div>
                  <div style="background: #f9fafb; padding: 2rem; border-radius: 0 0 12px 12px;">
                    <h2 style="color: #1f2937;">Olá, %s!</h2>
                    <p style="color: #4b5563;">Recebemos uma solicitação para redefinir a senha da sua conta.</p>
                    <p style="color: #4b5563;">Use o código abaixo para continuar. Ele é válido por <strong>15 minutos</strong>.</p>
                    <div style="text-align: center; margin: 2rem 0;">
                      <div style="display: inline-block; background: white; border: 2px solid #10b981; border-radius: 12px; padding: 1rem 2.5rem;">
                        <span style="font-size: 2.5rem; font-weight: bold; letter-spacing: 0.5rem; color: #059669;">%s</span>
                      </div>
                    </div>
                    <p style="color: #9ca3af; font-size: 13px;">Se você não solicitou isso, ignore este email. Sua senha permanece a mesma.</p>
                  </div>
                </div>
                """.formatted(nome, codigo);

            helper.setText(html, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new RuntimeException("Erro ao montar o email. Tente novamente.", e);
        }
    }
}
