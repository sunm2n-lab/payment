package com.sunm2n.pay.merchant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "merchant")
public class Merchant {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "api_key", nullable = false)
  private String apiKey;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  protected Merchant() {}

  public Merchant(String name, String apiKey) {
    this.name = name;
    this.apiKey = apiKey;
    this.createdAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getApiKey() {
    return apiKey;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
