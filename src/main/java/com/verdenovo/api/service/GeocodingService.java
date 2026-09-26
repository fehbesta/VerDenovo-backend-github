package com.verdenovo.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org/search";
    private static final String GEOAPIFY_URL = "https://api.geoapify.com/v1/geocode/search";
    private static final String BRASILAPI_CEP_URL = "https://brasilapi.com.br/api/cep/v2/";

    @Value("${GEOAPIFY_API_KEY:}")
    private String geoapifyApiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record Coordenadas(double latitude, double longitude) {}

    /**
     * Re-geocodificação estrita usada pelo botão administrativo. Só retorna
     * coordenadas quando o serviço confirma o número do imóvel; não usa o centro
     * do CEP ou da cidade como se fosse a posição exata do ponto.
     */
    public Optional<Coordenadas> geocodificarPrecisamente(
            String logradouro,
            String numero,
            String cep
    ) {
        String cepLimpo = limparCep(cep);
        String endereco = montarEndereco(logradouro, numero, cepLimpo);
        if (endereco.isBlank()) return Optional.empty();

        if (geoapifyApiKey != null && !geoapifyApiKey.isBlank()) {
            Optional<Coordenadas> geoapify = tentarGeoapify(endereco, numero);
            if (geoapify.isPresent()) return geoapify;
        }

        return tentarNominatimEnderecoExato(endereco, numero);
    }

    /**
     * Busca ampla usada em cadastros/backfill. Tenta o endereço antes do CEP;
     * CEP é apenas alternativa aproximada se não houver resultado pelo endereço.
     */
    public Optional<Coordenadas> geocodificar(String logradouro, String numero, String cep) {
        String cepLimpo = limparCep(cep);
        String endereco = montarEndereco(logradouro, numero, cepLimpo);

        if (geoapifyApiKey != null && !geoapifyApiKey.isBlank() && !endereco.isBlank()) {
            Optional<Coordenadas> geoapify = tentarGeoapify(endereco, numero);
            if (geoapify.isPresent()) return geoapify;
        }

        if (!endereco.isBlank()) {
            Optional<Coordenadas> nominatim = tentarNominatimEnderecoExato(endereco, numero);
            if (nominatim.isPresent()) return nominatim;
        }

        if (!cepLimpo.isBlank()) {
            Optional<Coordenadas> brasilApi = tentarBrasilApi(cepLimpo);
            if (brasilApi.isPresent()) return brasilApi;

            return tentarNominatim("postalcode=" + cepLimpo + "&country=Brazil");
        }

        return Optional.empty();
    }

    private Optional<Coordenadas> tentarGeoapify(String endereco, String numeroEsperado) {
        try {
            String query = "text=" + URLEncoder.encode(endereco, StandardCharsets.UTF_8)
                    + "&format=json&limit=5&filter=countrycode:br&lang=pt"
                    + "&apiKey=" + URLEncoder.encode(geoapifyApiKey, StandardCharsets.UTF_8);
            URI uri = URI.create(GEOAPIFY_URL + "?" + query);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "VerDenovo-TCC-App/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) {
                log.warn("Geoapify retornou status {} na busca de endereço", response.statusCode());
                return Optional.empty();
            }

            JsonNode results = objectMapper.readTree(response.body()).path("results");
            if (!results.isArray()) return Optional.empty();

            String numeroNormalizado = normalizarNumero(numeroEsperado);
            for (JsonNode result : results) {
                JsonNode rank = result.path("rank");
                String numeroEncontrado = normalizarNumero(result.path("housenumber").asText(""));

                // Quando o cadastro tem número, exige que o resultado confirme esse número.
                if (!numeroNormalizado.isBlank()
                        && !numeroNormalizado.equals(numeroEncontrado)) {
                    continue;
                }

                if (!numeroNormalizado.isBlank()
                        && rank.path("confidence_building_level").asDouble(0) <= 0) {
                    continue;
                }

                Optional<Coordenadas> coordenadas = lerCoordenadas(result.path("properties"));
                if (coordenadas.isPresent()) {
                    log.info("Endereço localizado pelo Geoapify: {}", result.path("formatted").asText());
                    return coordenadas;
                }
            }
        } catch (Exception e) {
            log.warn("Falha ao consultar Geoapify: {}", e.getMessage());
        }

        return Optional.empty();
    }

    private Optional<Coordenadas> tentarNominatimEnderecoExato(
            String endereco,
            String numeroEsperado
    ) {
        try {
            String query = "q=" + URLEncoder.encode(endereco, StandardCharsets.UTF_8)
                    + "&addressdetails=1";
            URI uri = URI.create(NOMINATIM_URL + "?format=jsonv2&limit=5&" + query);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "VerDenovo-TCC-App/1.0 (contato: verdenovo.support@gmail.com)")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) return Optional.empty();

            JsonNode results = objectMapper.readTree(response.body());
            if (!results.isArray()) return Optional.empty();

            String numeroNormalizado = normalizarNumero(numeroEsperado);
            for (JsonNode result : results) {
                String numeroEncontrado = normalizarNumero(
                        result.path("address").path("house_number").asText("")
                );

                if (!numeroNormalizado.isBlank()
                        && !numeroNormalizado.equals(numeroEncontrado)) {
                    continue;
                }

                Optional<Coordenadas> coordenadas = lerCoordenadas(result);
                if (coordenadas.isPresent()) {
                    log.info("Endereço localizado pelo Nominatim: {}", result.path("display_name").asText());
                    return coordenadas;
                }
            }
        } catch (Exception e) {
            log.warn("Falha ao consultar Nominatim por endereço: {}", e.getMessage());
        }

        return Optional.empty();
    }

    private Optional<Coordenadas> tentarNominatim(String parametros) {
        try {
            URI uri = URI.create(NOMINATIM_URL + "?format=jsonv2&limit=1&" + parametros);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "VerDenovo-TCC-App/1.0 (contato: verdenovo.support@gmail.com)")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) return Optional.empty();

            JsonNode results = objectMapper.readTree(response.body());
            if (!results.isArray() || results.isEmpty()) return Optional.empty();
            return lerCoordenadas(results.get(0));
        } catch (Exception e) {
            log.warn("Falha ao consultar Nominatim por CEP: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Coordenadas> tentarBrasilApi(String cepLimpo) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BRASILAPI_CEP_URL + cepLimpo))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "VerDenovo-TCC-App/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) return Optional.empty();

            JsonNode coordinates = objectMapper.readTree(response.body())
                    .path("location")
                    .path("coordinates");
            if (coordinates.path("latitude").isMissingNode()
                    || coordinates.path("longitude").isMissingNode()) {
                return Optional.empty();
            }

            double latitude = coordinates.path("latitude").asDouble();
            double longitude = coordinates.path("longitude").asDouble();
            if (latitude == 0 && longitude == 0) return Optional.empty();

            return Optional.of(new Coordenadas(latitude, longitude));
        } catch (Exception e) {
            log.info("BrasilAPI não retornou coordenadas para o CEP {}: {}", cepLimpo, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Coordenadas> lerCoordenadas(JsonNode result) {
        try {
            double latitude = Double.parseDouble(result.path("lat").asText());
            double longitude = Double.parseDouble(result.path("lon").asText());
            if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                return Optional.empty();
            }
            return Optional.of(new Coordenadas(latitude, longitude));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String montarEndereco(String logradouro, String numero, String cepLimpo) {
        if (logradouro == null || logradouro.isBlank()) return "";

        String rua = logradouro.trim();
        String resto = "";
        int virgula = rua.indexOf(',');
        if (virgula >= 0) {
            resto = rua.substring(virgula);
            rua = rua.substring(0, virgula);
        }

        StringBuilder endereco = new StringBuilder(rua);
        if (numero != null && !numero.isBlank() && !"S/N".equalsIgnoreCase(numero.trim())) {
            endereco.append(", ").append(numero.trim());
        }
        endereco.append(resto);
        if (cepLimpo != null && !cepLimpo.isBlank()) {
            endereco.append(", ").append(cepLimpo);
        }
        endereco.append(", Brasil");
        return endereco.toString();
    }

    private String limparCep(String cep) {
        return cep == null ? "" : cep.replaceAll("\\D", "");
    }

    private String normalizarNumero(String numero) {
        return numero == null ? "" : numero.trim().replaceAll("\\s+", "").toLowerCase();
    }
}
