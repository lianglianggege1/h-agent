package com.h.backend.knowledge.application;

import com.h.backend.knowledge.infrastructure.config.KnowledgeProperties;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Text splitting and embedding only; no generative model, routing or query expansion. */
@Component
public class KnowledgeTextIndexer {
    private final EmbeddingModel model;
    private final EmbeddingStore<TextSegment> store;
    private final KnowledgeProperties properties;
    public KnowledgeTextIndexer(EmbeddingModel model, EmbeddingStore<TextSegment> store, KnowledgeProperties properties) {
        this.model=model; this.store=store; this.properties=properties;
    }
    public int index(Long user,Long document,int version,String title,String text) {
        return index(user,document,version,title,text,properties.getSplit().getChunkSize(),properties.getSplit().getChunkOverlap());
    }
    public int index(Long user,Long document,int version,String title,String text,int chunkSize,int overlap) {
        if(text==null || text.isBlank()) throw new IllegalArgumentException("Knowledge text is empty");
        var segments=DocumentSplitters.recursive(chunkSize, overlap)
                .split(Document.from(text));
        List<String> ids=new ArrayList<>();
        for(int i=0;i<segments.size();i++) {
            segments.get(i).metadata().put("userId",user.toString()).put("docId",document.toString())
                    .put("fileName",title).put("revision",Integer.toString(version)).put("index",Integer.toString(i));
            ids.add(UUID.nameUUIDFromBytes(("knowledge:"+document+":"+version+":"+i).getBytes(StandardCharsets.UTF_8)).toString());
        }
        var embeddings=model.embedAll(segments).content();
        store.addAll(ids,embeddings,segments);
        return segments.size();
    }
}
