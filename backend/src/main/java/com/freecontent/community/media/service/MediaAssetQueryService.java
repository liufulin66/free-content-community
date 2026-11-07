package com.freecontent.community.media.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.media.dto.MediaAssetView;

public interface MediaAssetQueryService {
    MediaAssetView getMyAsset(String assetId, SessionPrincipal principal, String sourceIp);
}
