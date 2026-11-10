package com.freecontent.community.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(requiredProperties = {"assetId","url","expiresAt","contentType"})
public record MediaAccess(@Schema(minLength = 1,maxLength = 64,pattern = "^[A-Za-z0-9_-]+$") String assetId,
        @Schema(format = "uri") String url,Instant expiresAt,String contentType) {
    @Override public String toString() { return "MediaAccess[assetId="+assetId+",url=REDACTED,expiresAt="+expiresAt+",contentType="+contentType+"]"; }
}
