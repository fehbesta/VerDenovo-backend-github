package com.verdenovo.api.service;

import com.verdenovo.api.entity.Categoria;
import com.verdenovo.api.entity.Ponto;
import com.verdenovo.api.repository.CategoriaRepository;
import com.verdenovo.api.repository.PontoRepository;
import com.verdenovo.api.repository.UsuarioRepository;
import com.verdenovo.api.config.AddressChangeTriggerInitializer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PontoService {

    private static final Logger log = LoggerFactory.getLogger(PontoService.class);
    private static final String STATUS_PENDENTE = "PENDENTE";
    private static final String STATUS_PROCESSANDO = "PROCESSANDO";
    private static final String STATUS_SUCESSO = "SUCESSO";
    private static final String STATUS_FALHA = "FALHA";
    private static final long LEASE_PROCESSAMENTO_MINUTOS = 2;

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

    @Autowired
    private AddressChangeTriggerInitializer addressChangeTriggerInitializer;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${geocoding.retry-days:7}")
    private long intervaloNovaTentativaDias;

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
            marcarGeocodificacaoPendente(ponto);
            pontoRepository.saveAndFlush(ponto);
            processarPontoPendente(ponto.getId(), "edicao");
            return pontoRepository.findById(ponto.getId()).orElse(ponto);
        }

        return pontoRepository.save(ponto);
    }

    @Transactional
    public Ponto criarPonto(Ponto ponto, String emailLogado) {
        boolean coordenadasRecebidasDoFormulario = ponto.getLatitude() != null
                && ponto.getLongitude() != null;
        log.info("[Geocoding] operacao=cadastro pontoId=pendente ruaInformada={} numeroInformado={} cepInformado={} latFormulario={} lonFormulario={}",
                ponto.getLogradouro() != null && !ponto.getLogradouro().isBlank(),
                ponto.getNumero() != null && !ponto.getNumero().isBlank(),
                ponto.getCep() != null && !ponto.getCep().isBlank(),
                ponto.getLatitude(), ponto.getLongitude());

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

        marcarGeocodificacaoPendente(ponto);
        Ponto salvo = pontoRepository.saveAndFlush(ponto);
        log.info("[Geocoding] operacao=cadastro pontoId={} marcado como pendente; formularioTinhaCoordenadas={}",
                salvo.getId(), coordenadasRecebidasDoFormulario);
        processarPontoPendente(salvo.getId(), "cadastro");
        Ponto persistido = pontoRepository.findById(salvo.getId()).orElse(salvo);
        entityManager.refresh(persistido);
        return persistido;
    }

    public Ponto regeocodificarPonto(Long id) {
        Ponto ponto = pontoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ponto não encontrado."));
        String execucaoId = java.util.UUID.randomUUID().toString();
        if (pontoRepository.reivindicarGeocodificacaoManual(id, leaseProcessamentoAte(), execucaoId) == 0) {
            throw new RuntimeException("A localização deste ponto já está sendo processada.");
        }
        processarPontoReivindicado(id, "correcao_manual", execucaoId);
        Ponto atualizado = pontoRepository.findById(id).orElse(ponto);
        if (!STATUS_SUCESSO.equals(atualizado.getGeocodificacaoStatus())) {
            throw new RuntimeException("Não foi possível localizar o endereço com confiança suficiente.");
        }
        return atualizado;
    }

    public boolean iniciarRegeocodificacaoEmLote() {
        if (!reprocessamentoEmAndamento.compareAndSet(false, true)) return false;
        reprocessamentoProcessados.set(0);
        reprocessamentoAtualizados.set(0);
        estadoReprocessamento = "RUNNING";
        CompletableFuture.runAsync(() -> {
            try {
                processarFilaGeocodificacao();
                estadoReprocessamento = "COMPLETED";
            } catch (Exception e) {
                estadoReprocessamento = "FAILED";
                log.error("[Geocoding] Worker da fila falhou ({})", e.getClass().getSimpleName());
            } finally {
                reprocessamentoEmAndamento.set(false);
            }
        });
        return true;
    }

    @Scheduled(
            fixedDelayString = "${geocoding.scan-interval-ms:30000}",
            initialDelayString = "${geocoding.scan-initial-delay-ms:15000}"
    )
    public void agendarProcessamentoDePendencias() {
        iniciarRegeocodificacaoEmLote();
    }

    @Scheduled(
            fixedDelayString = "${geocoding.fallback-scan-interval-ms:300000}",
            initialDelayString = "${geocoding.fallback-scan-initial-delay-ms:30000}"
    )
    public void agendarReconciliaçãoFallback() {
        if (!addressChangeTriggerInitializer.isInstalled() && addressChangeTriggerInitializer.isFallbackEnabled()) {
            reconciliarAlteracoesExternasPorHash();
        }
    }

    /** Fallback quando o login SQL não pode instalar triggers: identifica somente hashes alterados. */
    private void reconciliarAlteracoesExternasPorHash() {
        long ultimoId = 0;
        int alterados = 0;
        List<PontoRepository.EstadoEnderecoGeocodificacao> pagina;
        do {
            pagina = pontoRepository.findTop500ByIdGreaterThanOrderByIdAsc(ultimoId);
            for (PontoRepository.EstadoEnderecoGeocodificacao estado : pagina) {
                ultimoId = estado.getId();
                String hashAtual = hashEndereco(estado.getLogradouro(), estado.getNumero(), estado.getCep());
                if (!Objects.equals(hashAtual, estado.getGeocodificacaoEnderecoHash())) {
                    Ponto ponto = pontoRepository.findById(estado.getId()).orElse(null);
                    if (ponto != null && !Objects.equals(hashAtual,
                            hashEndereco(ponto.getLogradouro(), ponto.getNumero(), ponto.getCep()))) {
                        marcarGeocodificacaoPendente(ponto);
                        pontoRepository.save(ponto);
                        alterados++;
                    }
                }
            }
        } while (pagina.size() == 500);
        if (alterados > 0) {
            log.info("[Geocoding] Fallback por hash detectou {} endereço(s) novo(s) ou alterado(s)", alterados);
        }
    }

    public Map<String, Object> obterStatusRegeocodificacaoEmLote() {
        long pendentes = pontoRepository.countByGeocodificacaoStatus(STATUS_PENDENTE);
        long processando = pontoRepository.countByGeocodificacaoStatus(STATUS_PROCESSANDO);
        long falhas = pontoRepository.countByGeocodificacaoStatus(STATUS_FALHA);
        boolean falhaElegivel = !pontoRepository
                .findByGeocodificacaoStatusAndGeocodificacaoTentativaAposLessThanEqualOrderByIdAsc(
                        STATUS_FALHA, LocalDateTime.now()).isEmpty();
        return Map.of(
                "estado", estadoReprocessamento,
                "emAndamento", reprocessamentoEmAndamento.get() || pendentes > 0 || processando > 0 || falhaElegivel,
                "processados", reprocessamentoProcessados.get(),
                "atualizados", reprocessamentoAtualizados.get(),
                "pendentes", pendentes,
                "processando", processando,
                "falhas", falhas
        );
    }

    private void processarFilaGeocodificacao() {
        while (!Thread.currentThread().isInterrupted()) {
            LocalDateTime agora = LocalDateTime.now();
            pontoRepository.reenfileirarReivindicacoesExpiradas(agora);
            List<Ponto> candidatos = new ArrayList<>(
                    pontoRepository.findByGeocodificacaoStatusOrderByIdAsc(STATUS_PENDENTE));
            candidatos.addAll(pontoRepository
                    .findByGeocodificacaoStatusAndGeocodificacaoTentativaAposLessThanEqualOrderByIdAsc(
                            STATUS_FALHA, agora));
            if (candidatos.isEmpty()) return;

            boolean reivindicouAlgum = false;
            for (Ponto candidato : candidatos) {
                LocalDateTime tentativaAgora = LocalDateTime.now();
                String execucaoId = java.util.UUID.randomUUID().toString();
                if (pontoRepository.reivindicarGeocodificacaoPendente(
                        candidato.getId(), tentativaAgora, tentativaAgora.plusMinutes(LEASE_PROCESSAMENTO_MINUTOS),
                        execucaoId) == 0) {
                    continue;
                }
                reivindicouAlgum = true;
                reprocessamentoProcessados.incrementAndGet();
                processarPontoReivindicado(candidato.getId(), "fila_automatica", execucaoId);
            }
            if (!reivindicouAlgum) return;
        }
    }

    private void processarPontoPendente(Long pontoId, String operacao) {
        LocalDateTime agora = LocalDateTime.now();
        String execucaoId = java.util.UUID.randomUUID().toString();
        if (pontoRepository.reivindicarGeocodificacaoPendente(
                pontoId, agora, agora.plusMinutes(LEASE_PROCESSAMENTO_MINUTOS), execucaoId) != 0) {
            processarPontoReivindicado(pontoId, operacao, execucaoId);
        }
    }

    private void processarPontoReivindicado(Long pontoId, String operacao, String execucaoId) {
        Ponto ponto = pontoRepository.findById(pontoId).orElse(null);
        if (ponto == null) return;
        if (!execucaoId.equals(ponto.getGeocodificacaoExecucaoId())
                || !STATUS_PROCESSANDO.equals(ponto.getGeocodificacaoStatus())) {
            log.info("[Geocoding] operacao={} pontoId={} reivindicação substituída antes da consulta", operacao, pontoId);
            return;
        }

        String hashTentado = hashEndereco(ponto.getLogradouro(), ponto.getNumero(), ponto.getCep());
        ponto.setGeocodificacaoEnderecoHash(hashTentado);
        pontoRepository.saveAndFlush(ponto);
        log.info("[Geocoding] operacao={} pontoId={} tentativa iniciada", operacao, pontoId);

        GeocodingService.Coordenadas coordenadas;
        try {
            coordenadas = geocodingService
                    .geocodificarPrecisamente(ponto.getLogradouro(), ponto.getNumero(), ponto.getCep(), operacao, pontoId)
                    .orElse(null);
        } catch (Exception e) {
            coordenadas = null;
            log.warn("[Geocoding] operacao={} pontoId={} falha inesperada ({})",
                    operacao, pontoId, e.getClass().getSimpleName());
        }

        Ponto atual = pontoRepository.findById(pontoId).orElse(null);
        if (atual == null) return;
        String hashAtual = hashEndereco(atual.getLogradouro(), atual.getNumero(), atual.getCep());
        boolean aindaEhDonoDaReivindicacao = STATUS_PROCESSANDO.equals(atual.getGeocodificacaoStatus())
                && execucaoId.equals(atual.getGeocodificacaoExecucaoId());
        if (!Objects.equals(hashTentado, hashAtual) || !aindaEhDonoDaReivindicacao) {
            if (aindaEhDonoDaReivindicacao) {
                atual.setGeocodificacaoStatus(STATUS_PENDENTE);
                atual.setGeocodificacaoExecucaoId(null);
                atual.setGeocodificacaoEnderecoHash(hashAtual);
                atual.setGeocodificacaoTentativaApos(null);
                pontoRepository.save(atual);
            }
            log.info("[Geocoding] operacao={} pontoId={} tentativa obsoleta descartada; pendência preservada",
                    operacao, pontoId);
            return;
        }

        atual.setGeocodificacaoEnderecoHash(hashTentado);
        if (coordenadas != null) {
            atual.setLatitude(coordenadas.latitude());
            atual.setLongitude(coordenadas.longitude());
            atual.setGeocodificacaoStatus(STATUS_SUCESSO);
            atual.setGeocodificacaoTentativaApos(null);
            atual.setGeocodificacaoExecucaoId(null);
            reprocessamentoAtualizados.incrementAndGet();
            log.info("[Geocoding] operacao={} pontoId={} resultado=SUCESSO coordenadasAplicadas lat={} lon={}",
                    operacao, pontoId, coordenadas.latitude(), coordenadas.longitude());
        } else {
            atual.setGeocodificacaoStatus(STATUS_FALHA);
            atual.setGeocodificacaoTentativaApos(LocalDateTime.now().plusDays(intervaloNovaTentativaDias));
            atual.setGeocodificacaoExecucaoId(null);
            log.info("[Geocoding] operacao={} pontoId={} resultado=FALHA; coordenadas atuais preservadas; nova tentativa após {}",
                    operacao, pontoId, atual.getGeocodificacaoTentativaApos());
        }
        Ponto salvo = pontoRepository.saveAndFlush(atual);
        log.info("[Geocoding] operacao={} pontoId={} estadoBanco={} latBanco={} lonBanco={}",
                operacao, pontoId, salvo.getGeocodificacaoStatus(), salvo.getLatitude(), salvo.getLongitude());
    }

    private void marcarGeocodificacaoPendente(Ponto ponto) {
        ponto.setGeocodificacaoEnderecoHash(hashEndereco(ponto.getLogradouro(), ponto.getNumero(), ponto.getCep()));
        ponto.setGeocodificacaoStatus(STATUS_PENDENTE);
        ponto.setGeocodificacaoTentativaApos(null);
        ponto.setGeocodificacaoExecucaoId(null);
    }

    private LocalDateTime leaseProcessamentoAte() {
        return LocalDateTime.now().plusMinutes(LEASE_PROCESSAMENTO_MINUTOS);
    }

    private String hashEndereco(String logradouro, String numero, String cep) {
        return GeocodingAddressFingerprint.of(logradouro, numero, cep);
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
