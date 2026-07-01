package com.h.otheragents.a2a.domain.service;

import com.h.otheragents.a2a.domain.model.CreativeWritingDraft;
import com.h.otheragents.a2a.domain.model.CreativeWritingRequest;

public interface CreativeWriter {

    CreativeWritingDraft writeDraft(CreativeWritingRequest request);
}
