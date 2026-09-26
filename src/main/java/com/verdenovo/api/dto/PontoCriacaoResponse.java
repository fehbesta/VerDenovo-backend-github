package com.verdenovo.api.dto;

public class PontoCriacaoResponse {
    private final String message;
    private final Long id;
    private final Double latitude;
    private final Double longitude;

    public PontoCriacaoResponse(String message, Long id, Double latitude, Double longitude) {
        this.message = message;
        this.id = id;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getMessage() {
        return message;
    }

    public Long getId() {
        return id;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }
}
