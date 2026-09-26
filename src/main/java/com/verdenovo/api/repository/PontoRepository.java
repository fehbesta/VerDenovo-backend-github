package com.verdenovo.api.repository;

import com.verdenovo.api.entity.Ponto;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PontoRepository extends JpaRepository<Ponto, Long> {
    interface EstadoEnderecoGeocodificacao {
        Long getId();
        String getLogradouro();
        String getNumero();
        String getCep();
        String getGeocodificacaoEnderecoHash();
    }

    List<Ponto> findByStatusPonto(String statusPonto);
    Optional<Ponto> findByEmailAndStatusPonto(String email, String statusPonto);
    List<Ponto> findByUsuarioId(Long usuarioId);
    List<Ponto> findByGeocodificacaoStatusOrderByIdAsc(String geocodificacaoStatus);
    List<Ponto> findByGeocodificacaoStatusAndGeocodificacaoTentativaAposLessThanEqualOrderByIdAsc(
            String geocodificacaoStatus, LocalDateTime geocodificacaoTentativaApos);
    long countByGeocodificacaoStatus(String geocodificacaoStatus);
    List<EstadoEnderecoGeocodificacao> findTop500ByIdGreaterThanOrderByIdAsc(Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Ponto p set p.geocodificacaoStatus = 'PROCESSANDO', "
            + "p.geocodificacaoTentativaApos = :leaseAte, p.geocodificacaoExecucaoId = :execucaoId "
            + "where p.id = :id and "
            + "(p.geocodificacaoStatus = 'PENDENTE' or "
            + "(p.geocodificacaoStatus = 'FALHA' and p.geocodificacaoTentativaApos <= :agora))")
    int reivindicarGeocodificacaoPendente(
            @Param("id") Long id,
            @Param("agora") LocalDateTime agora,
            @Param("leaseAte") LocalDateTime leaseAte,
            @Param("execucaoId") String execucaoId
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Ponto p set p.geocodificacaoStatus = 'PROCESSANDO', "
            + "p.geocodificacaoTentativaApos = :leaseAte, p.geocodificacaoExecucaoId = :execucaoId "
            + "where p.id = :id and "
            + "(p.geocodificacaoStatus is null or p.geocodificacaoStatus <> 'PROCESSANDO')")
    int reivindicarGeocodificacaoManual(
            @Param("id") Long id,
            @Param("leaseAte") LocalDateTime leaseAte,
            @Param("execucaoId") String execucaoId
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Ponto p set p.geocodificacaoStatus = 'PENDENTE', "
            + "p.geocodificacaoTentativaApos = null, p.geocodificacaoExecucaoId = null "
            + "where p.geocodificacaoStatus = 'PROCESSANDO' "
            + "and p.geocodificacaoTentativaApos <= :agora")
    int reenfileirarReivindicacoesExpiradas(@Param("agora") LocalDateTime agora);
}
