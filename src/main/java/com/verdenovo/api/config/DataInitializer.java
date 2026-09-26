package com.verdenovo.api.config;

import com.verdenovo.api.entity.Categoria;
import com.verdenovo.api.entity.Ponto;
import com.verdenovo.api.entity.Usuario;
import com.verdenovo.api.repository.CategoriaRepository;
import com.verdenovo.api.repository.PontoRepository;
import com.verdenovo.api.repository.UsuarioRepository;
import com.verdenovo.api.service.GeocodingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PontoRepository pontoRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private GeocodingService geocodingService;

    @Value("${admin.email}")
    private String adminEmail;

    @Value("${admin.senha}")
    private String adminSenha;

    @Override
    public void run(String... args) throws Exception {
        if (categoriaRepository.count() == 0) {
            Categoria categoria = new Categoria();
            categoria.setNome("Geral");
            categoria.setDescricao("Categoria padrão para pontos de coleta");
            categoria.setStatusCategoria("ATIVO");
            categoriaRepository.save(categoria);
        }

        if (!usuarioRepository.existsByEmail(adminEmail)) {
            Usuario admin = new Usuario();
            admin.setNome("Administrador");
            admin.setEmail(adminEmail);
            admin.setSenha(passwordEncoder.encode(adminSenha));
            admin.setNivelAcesso("ADMIN");
            admin.setStatusUsuario("ATIVO");
            admin.setDataCadastro(LocalDateTime.now());
            usuarioRepository.save(admin);
        }

        backfillCoordenadas();
    }

    /**
     * Geocodifica, em segundo plano (sem travar a inicialização da aplicação),
     * pontos já existentes que ainda não têm latitude/longitude — por exemplo,
     * pontos cadastrados antes da funcionalidade de mapa ter sido adicionada.
     * Isso garante que o app mobile (que depende de coordenadas para os pins
     * no mapa) também enxergue pontos antigos sem precisar de migração manual.
     */
    private void backfillCoordenadas() {
        List<Ponto> pendentes = pontoRepository.findAll().stream()
                .filter(p -> p.getLatitude() == null || p.getLongitude() == null)
                .toList();

        if (pendentes.isEmpty()) {
            return;
        }

        log.info("Geocodificando {} ponto(s) sem coordenadas em segundo plano...", pendentes.size());

        Thread thread = new Thread(() -> {
            for (Ponto ponto : pendentes) {
                geocodingService.geocodificar(ponto.getLogradouro(), ponto.getNumero(), ponto.getCep())
                        .ifPresent(coords -> {
                            ponto.setLatitude(coords.latitude());
                            ponto.setLongitude(coords.longitude());
                            pontoRepository.save(ponto);
                        });
                try {
                    // Respeita a política de uso do Nominatim (máx. 1 req/s)
                    Thread.sleep(1100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("Backfill de coordenadas concluído.");
        });
        thread.setDaemon(true);
        thread.start();
    }
}
