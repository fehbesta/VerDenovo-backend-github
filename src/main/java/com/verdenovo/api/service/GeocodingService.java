package com.verdenovo.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

@Service
public class GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);
    private static final String NOMINATIM_URL =
            "https://nominatim.openstreetmap.org/search";
    private static final String BRASILAPI_CEP_URL =
            "https://brasilapi.com.br/api/cep/v2/";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record Coordenadas(double latitude, double longitude) {}

    public Optional<Coordenadas> geocodificar(
            String logradouro,
            String numero,
            String cep
    ) {
        String cepLimpo = cep == null ? "" : cep.replaceAll("\\D", "");

        // Primeiro tenta encontrar o endereço completo.
        String enderecoCompleto = montarEndereco(logradouro, numero, cepLimpo);
        if (!enderecoCompleto.isBlank()) {
            Optional<Coordenadas> peloEndereco = tentarNominatim(
                    "q=" + URLEncoder.encode(
                            enderecoCompleto,
                            StandardCharsets.UTF_8
                    )
            );
            if (peloEndereco.isPresent()) {
                return peloEndereco;
            }
        }

        // Se não encontrar a rua e o número, tenta aproximar pelo CEP.
        if (!cepLimpo.isBlank()) {
            Optional<Coordenadas> pelaBrasilApi = tentarBrasilApi(cepLimpo);
            if (pelaBrasilApi.isPresent()) {
                return pelaBrasilApi;
            }

            return tentarNominatim(
                    "postalcode=" + cepLimpo + "&country=Brazil"
            );
        }

        return Optional.empty();
    }

    private Optional<Coordenadas> tentarBrasilApi(String cepLimpo) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BRASILAPI_CEP_URL + cepLimpo))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "VerDenovo-TCC-App/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                return Optional.empty();
            }

            JsonNode raiz = objectMapper.readTree(response.body());
            JsonNode coordenadas = raiz.path("location").path("coordinates");

            if (coordenadas.isMissingNode()
                    || coordenadas.path("latitude").isMissingNode()
                    || coordenadas.path("longitude").isMissingNode()) {
                return Optional.empty();
            }

            double latitude = coordenadas.path("latitude").asDouble();
            double longitude = coordenadas.path("longitude").asDouble();

            if (latitude == 0 && longitude == 0) {
                return Optional.empty();
            }

            return Optional.of(new Coordenadas(latitude, longitude));
        } catch (Exception e) {
            log.info(
                    "BrasilAPI não retornou coordenadas para o CEP {}: {}",
                    cepLimpo,
                    e.getMessage()
            );
            return Optional.empty();
        }
    }

    private Optional<Coordenadas> tentarNominatim(String parametros) {
        try {
            URI uri = URI.create(
                    NOMINATIM_URL + "?format=json&limit=1&" + parametros
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "VerDenovo-TCC-App/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                log.warn(
                        "Nominatim retornou status {} para '{}'",
                        response.statusCode(),
                        parametros
                );
                return Optional.empty();
            }

            JsonNode resultados = objectMapper.readTree(response.body());
            if (!resultados.isArray() || resultados.isEmpty()) {
                return Optional.empty();
            }

            JsonNode primeiro = resultados.get(0);
            double latitude = Double.parseDouble(primeiro.path("lat").asText());
            double longitude = Double.parseDouble(primeiro.path("lon").asText());

            return Optional.of(new Coordenadas(latitude, longitude));
        } catch (Exception e) {
            log.warn(
                    "Falha ao consultar Nominatim ('{}'): {}",
                    parametros,
                    e.getMessage()
            );
            return Optional.empty();
        }
    }

    private String montarEndereco(
            String logradouro,
            String numero,
            String cepLimpo
    ) {
        if (logradouro == null || logradouro.isBlank()) {
            return "";
        }

        String rua = logradouro.trim();
        String complementoEndereco = "";

        int virgula = rua.indexOf(',');
        if (virgula >= 0) {
            complementoEndereco = rua.substring(virgula);
            rua = rua.substring(0, virgula);
        }

        StringBuilder endereco = new StringBuilder(rua);

        if (numero != null
                && !numero.isBlank()
                && !"S/N".equalsIgnoreCase(numero.trim())) {
            endereco.append(", ").append(numero.trim());
        }

        endereco.append(complementoEndereco);

        if (cepLimpo != null && !cepLimpo.isBlank()) {
            endereco.append(", ").append(cepLimpo);
        }

        endereco.append(", Brasil");
        return endereco.toString();
    }
}