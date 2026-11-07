package com.freecontent.community.media.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.media.dto.MediaTaskView;

public interface MediaTaskQueryService {
    MediaTaskView getMyTask(String taskId, SessionPrincipal principal, String sourceIp);
}
