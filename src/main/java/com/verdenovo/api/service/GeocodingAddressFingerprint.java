package com.verdenovo.api.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Endereço persistido como impressão digital; evita registrar ou armazenar o endereço em claro. */
public final class GeocodingAddressFingerprint {

    private GeocodingAddressFingerprint() {}

    public static String of(String logradouro, String numero, String cep) {
        String conteudo = normalizar(logradouro) + "\u0000" + normalizar(numero) + "\u0000" + normalizar(cep);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(conteudo.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    private static String normalizar(String valor) {
        return valor == null ? "" : valor.trim().toLowerCase(Locale.ROOT);
    }
}
