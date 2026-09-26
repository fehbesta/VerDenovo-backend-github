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

/**
 * Converte um endereço (CEP + logradouro + número) em coordenadas de latitude/longitude,
 * tentando, em ordem, fontes gratuitas e sem necessidade de chave de API:
 *
 *  1) BrasilAPI (CEP v2) — específica para CEPs brasileiros, geralmente mais precisa
 *     para o caso de uso daqui do que uma busca genérica por texto.
 *  2) Nominatim (OpenStreetMap), busca estruturada só pelo CEP — evita ambiguidade de
 *     nomes de rua repetidos em cidades diferentes.
 *  3) Nominatim, busca livre pelo endereço completo (rua + número + bairro/cidade) —
 *     usada quando as duas anteriores não encontram nada (comum em condomínios/
 *     loteamentos fechados, que costumam não estar bem mapeados nessas bases).
 *  4) Nominatim, busca só por "Cidade/UF" — último recurso. Não é a posição exata do
 *     endereço, mas garante que o ponto apareça no mapa (ao menos na cidade certa) em
 *     vez de simplesmente não aparecer. Um pequeno deslocamento aleatório (porém fixo
 *     para o mesmo endereço) evita que vários pontos "aproximados" da mesma cidade
 *     fiquem todos exatamente um em cima do outro no mapa.
 *
 * Se todas falharem (sem internet, cidade não encontrada, APIs fora do ar etc.),
 * o método retorna Optional.empty() e o cadastro do Ponto continua normalmente sem
 * coordenadas — o ponto simplesmente não aparece no mapa do app mobile até ser
 * corrigido/re-geocodificado (o que acontece automaticamente no próximo restart do
 * backend, via DataInitializer).
 */
@Service
public class GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);
    private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org/search";
    private static final String BRASILAPI_CEP_URL = "https://brasilapi.com.br/api/cep/v2/";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record Coordenadas(double latitude, double longitude) {}

    public Optional<Coordenadas> geocodificar(String logradouro, String numero, String cep) {
        String cepLimpo = cep == null ? "" : cep.replaceAll("\\D", "");

        if (!cepLimpo.isBlank()) {
            Optional<Coordenadas> viaBrasilApi = tentarBrasilApi(cepLimpo);
            if (viaBrasilApi.isPresent()) return viaBrasilApi;

            Optional<Coordenadas> viaNominatimCep = tentarNominatim("postalcode=" + cepLimpo + "&country=Brazil");
            if (viaNominatimCep.isPresent()) return viaNominatimCep;
        }

        String enderecoLivre = montarEnderecoLivre(logradouro, numero, cepLimpo);
        if (!enderecoLivre.isBlank()) {
            Optional<Coordenadas> viaEnderecoLivre =
                    tentarNominatim("q=" + URLEncoder.encode(enderecoLivre, StandardCharsets.UTF_8));
            if (viaEnderecoLivre.isPresent()) return viaEnderecoLivre;
        }

        return geocodificarPorCidade(logradouro);
    }

    /**
     * Último recurso: geocodifica só a cidade/UF extraída do logradouro (ex: "Barueri/SP"),
     * com um pequeno deslocamento aleatório (mas fixo para o mesmo endereço) para não
     * empilhar vários pinos exatamente no mesmo ponto do mapa.
     */
    private Optional<Coordenadas> geocodificarPorCidade(String logradouro) {
        String cidadeUf = extrairCidadeUf(logradouro);
        if (cidadeUf.isBlank()) return Optional.empty();

        Optional<Coordenadas> resultado =
                tentarNominatim("q=" + URLEncoder.encode(cidadeUf + ", Brasil", StandardCharsets.UTF_8));
        if (resultado.isEmpty()) return Optional.empty();

        // Deslocamento de até ~600m, determinístico por endereço (mesmo texto -> mesmo
        // deslocamento), só para os pinos aproximados não ficarem todos sobrepostos.
        java.util.Random rnd = new java.util.Random(logradouro.hashCode());
        double jitterLat = (rnd.nextDouble() - 0.5) * 0.01;
        double jitterLon = (rnd.nextDouble() - 0.5) * 0.01;
        Coordenadas base = resultado.get();
        log.info("Endereço '{}' não encontrado com precisão; usando aproximação por cidade ({})", logradouro, cidadeUf);
        return Optional.of(new Coordenadas(base.latitude() + jitterLat, base.longitude() + jitterLon));
    }

    /** Extrai "Cidade/UF" do final do logradouro (formato: "Rua, Bairro - Cidade/UF"). */
    private String extrairCidadeUf(String logradouro) {
        if (logradouro == null || logradouro.isBlank()) return "";
        String[] partes = logradouro.split(" - ");
        String ultima = partes[partes.length - 1].trim();
        // Confirma que parece mesmo "Cidade/UF" (tem uma barra com 2 letras de UF depois)
        return ultima.matches(".+/[A-Za-z]{2}$") ? ultima : "";
    }

    /** Fonte 1: BrasilAPI — dados específicos de CEPs do Brasil. */
    private Optional<Coordenadas> tentarBrasilApi(String cepLimpo) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BRASILAPI_CEP_URL + cepLimpo))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "VerDenovo-TCC-App/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Optional.empty();

            JsonNode raiz = objectMapper.readTree(response.body());
            JsonNode coords = raiz.path("location").path("coordinates");
            if (coords.isMissingNode() || coords.path("latitude").isMissingNode()) {
                return Optional.empty();
            }
            double lat = coords.path("latitude").asDouble();
            double lon = coords.path("longitude").asDouble();
            if (lat == 0 && lon == 0) return Optional.empty();
            return Optional.of(new Coordenadas(lat, lon));
        } catch (Exception e) {
            log.info("BrasilAPI não retornou coordenadas para o CEP {}: {}", cepLimpo, e.getMessage());
            return Optional.empty();
        }
    }

    /** Fontes 2 e 3: Nominatim/OpenStreetMap. queryParams já deve vir url-encoded. */
    private Optional<Coordenadas> tentarNominatim(String queryParams) {
        try {
            URI uri = URI.create(NOMINATIM_URL + "?format=json&limit=1&" + queryParams);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "VerDenovo-TCC-App/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Nominatim retornou status {} para '{}'", response.statusCode(), queryParams);
                return Optional.empty();
            }

            JsonNode resultados = objectMapper.readTree(response.body());
            if (!resultados.isArray() || resultados.isEmpty()) {
                return Optional.empty();
            }

            JsonNode primeiro = resultados.get(0);
            double lat = Double.parseDouble(primeiro.get("lat").asText());
            double lon = Double.parseDouble(primeiro.get("lon").asText());
            return Optional.of(new Coordenadas(lat, lon));

        } catch (Exception e) {
            log.warn("Falha ao consultar Nominatim ('{}'): {}", queryParams, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Monta um endereço em texto livre com o número da casa logo após o nome da rua
     * (e não depois do bairro/cidade), que é o formato que geocodificadores entendem
     * melhor. Ex.: "Avenida Pedro Paulino, 51, Conjunto Habitacional - Setor D - Itapevi/SP"
     */
    private String montarEnderecoLivre(String logradouro, String numero, String cepLimpo) {
        if (logradouro == null || logradouro.isBlank()) return "";

        String rua = logradouro.trim();
        String resto = "";
        int virgula = rua.indexOf(',');
        if (virgula > -1) {
            resto = rua.substring(virgula); // ", Bairro - Cidade/UF"
            rua = rua.substring(0, virgula); // "Avenida Pedro Paulino"
        }

        StringBuilder sb = new StringBuilder(rua);
        if (numero != null && !numero.isBlank() && !"S/N".equalsIgnoreCase(numero.trim())) {
            sb.append(", ").append(numero.trim());
        }
        sb.append(resto);
        sb.append(", Brasil");
        return sb.toString();
    }
}
