package com.h.backend.outbound.domain;

import lombok.Data;

@Data
public class Contact {
    private Long id;
    private Long userId;
    private String phone;
    private String name;
    private String consentBasis;
    private boolean dnc;
    private long createdAt;
}
