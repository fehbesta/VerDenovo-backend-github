package com.verdenovo.api.service;

import com.verdenovo.api.entity.Categoria;
import com.verdenovo.api.entity.Ponto;
import com.verdenovo.api.repository.CategoriaRepository;
import com.verdenovo.api.repository.PontoRepository;
import com.verdenovo.api.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PontoService {

    private static final Logger log = LoggerFactory.getLogger(PontoService.class);

    @Autowired
    private PontoRepository pontoRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private GeocodingService geocodingService;

    private final AtomicBoolean reprocessamentoEmAndamento = new AtomicBoolean(false);
    private final AtomicInteger reprocessamentoProcessados = new AtomicInteger();
    private final AtomicInteger reprocessamentoAtualizados = new AtomicInteger();
    private volatile String estadoReprocessamento = "IDLE";

    public Ponto loginPonto(String email, String senha) {
        Ponto ponto = pontoRepository.findByEmailAndStatusPonto(email, "ATIVO")
                .orElseThrow(() -> new RuntimeException("Credenciais inválidas"));
        if (ponto.getSenha() == null || !passwordEncoder.matches(senha, ponto.getSenha())) {
            throw new RuntimeException("Credenciais inválidas");
        }
        return ponto;
    }

    public Ponto atualizarPonto(Long id, Ponto pontoAtualizado, String emailLogado) {
        Ponto ponto = pontoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ponto não encontrado"));
        boolean isAdmin = usuarioRepository.findByEmail(emailLogado)
                .map(u -> "ADMIN".equals(u.getNivelAcesso()))
                .orElse(false);
        if (!isAdmin) {
            Long usuarioId = usuarioRepository.findByEmail(emailLogado).map(u -> u.getId()).orElse(null);
            if (usuarioId == null || !usuarioId.equals(ponto.getUsuarioId())) {
                throw new RuntimeException("Sem permissão para editar este ponto.");
            }
        }

        boolean enderecoMudou = !Objects.equals(ponto.getCep(), pontoAtualizado.getCep())
                || !Objects.equals(ponto.getNumero(), pontoAtualizado.getNumero())
                || !Objects.equals(ponto.getLogradouro(), pontoAtualizado.getLogradouro());

        // CNPJ não é editável por este formulário (campo somente-leitura no frontend),
        // então propositalmente não é sobrescrito aqui — evita apagar o valor gravado no cadastro.
        ponto.setNome(pontoAtualizado.getNome());
        ponto.setCep(pontoAtualizado.getCep());
        ponto.setNumero(pontoAtualizado.getNumero());
        ponto.setComplemento(pontoAtualizado.getComplemento());
        ponto.setLogradouro(pontoAtualizado.getLogradouro());
        ponto.setTelefone(pontoAtualizado.getTelefone());
        ponto.setHoraFuncionamento(pontoAtualizado.getHoraFuncionamento());
        ponto.setMaterial(pontoAtualizado.getMaterial());
        ponto.setDescricao(pontoAtualizado.getDescricao());

        if (enderecoMudou) {
            geocodificarPrecisamenteEAtribuir(ponto);
        }

        return pontoRepository.save(ponto);
    }

    public Ponto criarPonto(Ponto ponto, String emailLogado) {
        if (emailLogado != null) {
            usuarioRepository.findByEmail(emailLogado).ifPresent(u -> {
                if ("ADMIN".equals(u.getNivelAcesso())) {
                    vincularPontoAdmin(ponto, emailLogado);
                } else {
                    vincularPontoUsuario(ponto, u.getId());
                }
            });
        }

        if (ponto.getCnpj() != null) {
            ponto.setCnpj(ponto.getCnpj().replaceAll("\\D", ""));
        }

        codificarSenha(ponto);
        ponto.setDataCadastro(LocalDateTime.now());
        if (ponto.getStatusPonto() == null) ponto.setStatusPonto("PENDENTE");
        if (ponto.getCategoriaId() == null) {
            ponto.setCategoriaId(obterCategoriaPadraoId());
        }

        geocodificarPrecisamenteEAtribuir(ponto);

        return pontoRepository.save(ponto);
    }

    public Ponto regeocodificarPonto(Long id) {
        Ponto ponto = pontoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ponto não encontrado."));
        GeocodingService.Coordenadas coordenadas = geocodingService
                .geocodificarPrecisamente(ponto.getLogradouro(), ponto.getNumero(), ponto.getCep())
                .orElseThrow(() -> new RuntimeException(
                        "Não foi possível localizar o endereço. Confira rua, número e CEP."));
        ponto.setLatitude(coordenadas.latitude());
        ponto.setLongitude(coordenadas.longitude());
        return pontoRepository.save(ponto);
    }

    public boolean iniciarRegeocodificacaoEmLote() {
        if (!reprocessamentoEmAndamento.compareAndSet(false, true)) return false;
        reprocessamentoProcessados.set(0);
        reprocessamentoAtualizados.set(0);
        estadoReprocessamento = "RUNNING";
        CompletableFuture.runAsync(this::regeocodificarTodosPontos);
        return true;
    }

    public Map<String, Object> obterStatusRegeocodificacaoEmLote() {
        return Map.of(
                "estado", estadoReprocessamento,
                "emAndamento", reprocessamentoEmAndamento.get(),
                "processados", reprocessamentoProcessados.get(),
                "atualizados", reprocessamentoAtualizados.get()
        );
    }

    private void regeocodificarTodosPontos() {
        try {
            List<Ponto> pontos = pontoRepository.findAll();
            Map<String, GeocodingService.Coordenadas> coordenadasPorEndereco = new HashMap<>();
            Set<String> enderecosConsultados = new HashSet<>();
            List<Ponto> paraSalvar = new ArrayList<>();

            for (Ponto ponto : pontos) {
                String chaveEndereco = chaveEndereco(ponto);
                if (chaveEndereco.isBlank()) {
                    log.info("[Geocoding] Ponto id={} ignorado no lote: endereço insuficiente", ponto.getId());
                    reprocessamentoProcessados.incrementAndGet();
                    continue;
                }

                GeocodingService.Coordenadas coordenadas = coordenadasPorEndereco.get(chaveEndereco);
                if (enderecosConsultados.add(chaveEndereco)) {
                    coordenadas = geocodingService.geocodificarPrecisamente(
                                    ponto.getLogradouro(), ponto.getNumero(), ponto.getCep())
                            .orElse(null);
                    if (coordenadas != null) coordenadasPorEndereco.put(chaveEndereco, coordenadas);
                } else if (coordenadas == null) {
                    log.info("[Geocoding] Ponto id={} mantido: endereço repetido já consultado sem resultado preciso",
                            ponto.getId());
                }

                if (coordenadas != null) {
                    ponto.setLatitude(coordenadas.latitude());
                    ponto.setLongitude(coordenadas.longitude());
                    paraSalvar.add(ponto);
                    reprocessamentoAtualizados.incrementAndGet();
                    log.info("[Geocoding] Ponto id={} atualizado no reprocessamento em lote", ponto.getId());
                } else {
                    log.info("[Geocoding] Ponto id={} mantido: nenhum resultado preciso validado", ponto.getId());
                }
                reprocessamentoProcessados.incrementAndGet();
            }

            pontoRepository.saveAll(paraSalvar);
            estadoReprocessamento = "COMPLETED";
        } catch (Exception e) {
            estadoReprocessamento = "FAILED";
            log.error("[Geocoding] Reprocessamento em lote falhou ({})", e.getClass().getSimpleName());
        } finally {
            reprocessamentoEmAndamento.set(false);
        }
    }

    private String chaveEndereco(Ponto ponto) {
        String logradouro = ponto.getLogradouro() == null ? "" : ponto.getLogradouro();
        String numero = ponto.getNumero() == null ? "" : ponto.getNumero();
        String cep = ponto.getCep() == null ? "" : ponto.getCep();
        return (logradouro + "|" + numero + "|" + cep)
                .trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /**
     * Tenta encontrar as coordenadas do endereço informado e preenche latitude/longitude
     * do Ponto. Se a geocodificação falhar (sem internet, endereço não encontrado etc.),
     * o cadastro continua normalmente sem coordenadas — o ponto só não terá pin no mapa
     * do app mobile até ser corrigido.
     */
    private void geocodificarPrecisamenteEAtribuir(Ponto ponto) {
        geocodingService.geocodificarPrecisamente(ponto.getLogradouro(), ponto.getNumero(), ponto.getCep())
                .ifPresentOrElse(coords -> {
                    ponto.setLatitude(coords.latitude());
                    ponto.setLongitude(coords.longitude());
                }, () -> log.info("[Geocoding] Nenhum resultado preciso validado para gravação do ponto id={}",
                        ponto.getId() == null ? "novo" : ponto.getId()));
    }

    /**
     * Retorna o id da categoria padrão ("Geral", criada automaticamente pelo DataInitializer)
     * para pontos cadastrados sem categoria específica. Se por algum motivo ela não existir,
     * cai para a primeira categoria ativa disponível.
     */
    private Long obterCategoriaPadraoId() {
        return categoriaRepository.findByStatusCategoria("ATIVO").stream()
                .filter(c -> "Geral".equalsIgnoreCase(c.getNome()))
                .findFirst()
                .or(() -> categoriaRepository.findByStatusCategoria("ATIVO").stream().findFirst())
                .map(Categoria::getId)
                .orElse(null);
    }

    private void vincularPontoAdmin(Ponto ponto, String emailLogado) {
        String emailDono = (ponto.getEmail() != null && !ponto.getEmail().isEmpty())
                ? ponto.getEmail() : emailLogado;
        usuarioRepository.findByEmail(emailDono)
                .ifPresent(dono -> ponto.setUsuarioId(dono.getId()));
        if (ponto.getEmail() == null || ponto.getEmail().isEmpty()) {
            ponto.setEmail(emailLogado);
        }
        ponto.setStatusPonto("ATIVO");
    }

    private void vincularPontoUsuario(Ponto ponto, Long usuarioId) {
        boolean jaTemPonto = pontoRepository.findByUsuarioId(usuarioId).stream()
                .anyMatch(p -> "ATIVO".equals(p.getStatusPonto()) || "PENDENTE".equals(p.getStatusPonto()));
        if (jaTemPonto) {
            throw new RuntimeException("Você já possui um ponto de coleta cadastrado.");
        }
        ponto.setUsuarioId(usuarioId);
        ponto.setStatusPonto("PENDENTE");
    }

    private void codificarSenha(Ponto ponto) {
        if (ponto.getSenha() != null && !ponto.getSenha().isEmpty()) {
            ponto.setSenha(passwordEncoder.encode(ponto.getSenha()));
        } else {
            ponto.setSenha(passwordEncoder.encode(java.util.UUID.randomUUID().toString().substring(0, 12)));
        }
    }
}
