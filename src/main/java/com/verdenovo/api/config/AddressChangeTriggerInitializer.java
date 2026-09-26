package com.verdenovo.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.beans.factory.annotation.Value;
import com.verdenovo.api.service.GeocodingAddressFingerprint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AddressChangeTriggerInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AddressChangeTriggerInitializer.class);
    private final JdbcTemplate jdbcTemplate;
    @Value("${geocoding.trigger.install-enabled:true}")
    private boolean installEnabled;
    private volatile boolean installed;
    private volatile boolean fallbackEnabled;

    public AddressChangeTriggerInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys.triggers WHERE name = ? AND parent_id = OBJECT_ID(N'dbo.Ponto')",
                    Integer.class, "trg_ponto_geocodificacao_endereco");
            if (count != null && count > 0) {
                installed = true;
                log.info("[Geocoding] Trigger de endereço encontrado em dbo.Ponto");
                return;
            }
        } catch (Exception e) {
            log.warn("[Geocoding] Não foi possível verificar o trigger em sys.triggers ({}); tentando instalação",
                    e.getClass().getSimpleName());
        }
        if (!installEnabled) {
            log.warn("[Geocoding] Instalação automática do trigger desativada; fallback por hash ativo");
            fallbackEnabled = inicializarHashesExistentes();
            return;
        }

        String sql = """
                CREATE OR ALTER TRIGGER dbo.trg_ponto_geocodificacao_endereco
                ON dbo.Ponto
                AFTER INSERT, UPDATE
                AS
                BEGIN
                    SET NOCOUNT ON;

                    UPDATE p
                    SET geocodificacao_status = 'PENDENTE',
                        geocodificacao_tentativa_apos = NULL,
                        geocodificacao_execucao_id = NULL
                    FROM dbo.Ponto p
                    INNER JOIN inserted i ON i.id = p.id
                    LEFT JOIN deleted d ON d.id = i.id
                    WHERE d.id IS NULL
                       OR ISNULL(i.logradouro, '') <> ISNULL(d.logradouro, '')
                       OR ISNULL(i.numero, '') <> ISNULL(d.numero, '')
                       OR ISNULL(i.cep, '') <> ISNULL(d.cep, '');
                END
                """;
        try {
            jdbcTemplate.execute(sql);
            installed = true;
            log.info("[Geocoding] Trigger SQL Server de novos/alterados endereços pronto");
        } catch (Exception e) {
            installed = false;
            log.warn("[Geocoding] Trigger não instalado ({}). Permissões necessárias: CREATE TRIGGER no banco "
                            + "e ALTER na tabela dbo.Ponto. Backend seguirá com reconciliação periódica por hash; "
                            + "não há geocodificação em massa.", e.getClass().getSimpleName());
            fallbackEnabled = inicializarHashesExistentes();
        }
    }

    public boolean isInstalled() {
        return installed;
    }

    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }

    /** Baseline somente de hash: registros anteriores não são enfileirados na primeira inicialização sem trigger. */
    private boolean inicializarHashesExistentes() {
        try {
            List<Object[]> registros = new ArrayList<>();
            jdbcTemplate.query("SELECT id, logradouro, numero, cep FROM dbo.Ponto "
                            + "WHERE geocodificacao_endereco_hash IS NULL AND geocodificacao_status IS NULL",
                    (RowCallbackHandler) rs -> registros.add(new Object[]{rs.getLong("id"), rs.getString("logradouro"),
                            rs.getString("numero"), rs.getString("cep")}));
            for (Object[] registro : registros) {
                String hash = GeocodingAddressFingerprint.of(
                        (String) registro[1], (String) registro[2], (String) registro[3]);
                jdbcTemplate.update("UPDATE dbo.Ponto SET geocodificacao_endereco_hash = ? "
                                + "WHERE id = ? AND geocodificacao_endereco_hash IS NULL "
                                + "AND geocodificacao_status IS NULL",
                        hash, registro[0]);
            }
            log.info("[Geocoding] Baseline de endereços existentes preparado para o fallback por hash");
            return true;
        } catch (Exception e) {
            log.error("[Geocoding] Fallback por hash indisponível; confirme SELECT/UPDATE na tabela dbo.Ponto ({})",
                    e.getClass().getSimpleName());
            return false;
        }
    }
}
