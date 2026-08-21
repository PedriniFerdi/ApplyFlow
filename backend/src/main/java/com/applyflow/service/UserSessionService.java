package com.applyflow.service;

import java.util.Map;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

@Service
public class UserSessionService {

    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    public UserSessionService(FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public void invalidateAll(String principalName) {
        invalidateOther(principalName, null);
    }

    public void invalidateOther(String principalName, String currentSessionId) {
        Map<String, ? extends Session> sessions = sessionRepository.findByPrincipalName(principalName);
        sessions.forEach((id, session) -> {
            if (currentSessionId == null || !currentSessionId.equals(session.getId())) {
                sessionRepository.deleteById(id);
            }
        });
    }
}
