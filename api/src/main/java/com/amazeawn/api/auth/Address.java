package com.amazeawn.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "addresses")
public class Address {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String line1;

    private String line2;

    @Column(nullable = false)
    private String city;

    private String region;

    @Column(name = "postal_code", nullable = false)
    private String postalCode;

    @Column(nullable = false)
    private String country;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    protected Address() {}

    public Address(UUID userId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getLabel() { return label; }
    public String getLine1() { return line1; }
    public String getLine2() { return line2; }
    public String getCity() { return city; }
    public String getRegion() { return region; }
    public String getPostalCode() { return postalCode; }
    public String getCountry() { return country; }
    public boolean isDefault() { return isDefault; }

    public void setLabel(String label) { this.label = label; }
    public void setLine1(String line1) { this.line1 = line1; }
    public void setLine2(String line2) { this.line2 = line2; }
    public void setCity(String city) { this.city = city; }
    public void setRegion(String region) { this.region = region; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    public void setCountry(String country) { this.country = country; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    public String formatted() {
        StringBuilder sb = new StringBuilder(line1);
        if (line2 != null && !line2.isBlank()) sb.append(", ").append(line2);
        sb.append(", ").append(city);
        if (region != null && !region.isBlank()) sb.append(", ").append(region);
        sb.append(" ").append(postalCode).append(", ").append(country);
        return sb.toString();
    }
}
