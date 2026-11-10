package com.h.backend.knowledge.infrastructure;

import com.h.backend.knowledge.application.*;
import com.h.backend.knowledge.infrastructure.config.KnowledgeProperties;
import com.h.backend.knowledge.infrastructure.persistence.KnowledgeToolRepository;
import com.h.backend.knowledge.infrastructure.persistence.KnowledgeToolRepository.Document;
import com.h.backend.memory.application.AgentMemoryContext;
import com.h.backend.memory.application.MemorySources;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/** User-owned knowledge. Frameworks supply identity; models supply the query and authored text. */
@Service
public class UserKnowledgeTools implements KnowledgeTools {
    private static final Logger log=LoggerFactory.getLogger(UserKnowledgeTools.class);
    private final KnowledgeToolRepository repo;
    private final EmbeddingModel embeddings;
    private final EmbeddingStore<TextSegment> vectors;
    private final KnowledgeTextIndexer indexer;
    private final KnowledgeProperties properties;
    private final MemorySources sources;
    private final ObjectMapper json;
    public UserKnowledgeTools(KnowledgeToolRepository repo,EmbeddingModel embeddings,EmbeddingStore<TextSegment> vectors,
                              KnowledgeTextIndexer indexer,KnowledgeProperties properties,MemorySources sources,ObjectMapper json) {
        this.repo=repo;this.embeddings=embeddings;this.vectors=vectors;this.indexer=indexer;
        this.properties=properties;this.sources=sources;this.json=json;
    }
    private static class Outcome extends RuntimeException {
        final String status;
        Outcome(String status){super(status);this.status=status;}
    }
    public record Mutation(AgentMemoryContext context,String tool,Map<String,Object> arguments,List<String> sources,int chunkSize,int overlap) {}
    @Override public String execute(AgentMemoryContext ctx,String call,String tool,Map<String,Object> args) {
        try {
            if(ctx==null) throw new Outcome("UNAUTHENTICATED");
            Map<String,Object> response=switch(tool) {
                case "knowledge_list" -> catalog(ctx.userId(),number(args,"cursor",0));
                case "knowledge_search" -> search(ctx.userId(),args);
                case "knowledge_read" -> read(ctx.userId(),args);
                case "knowledge_history" -> history(ctx.userId(),id(args),integer(args,"cursor",0));
                case "knowledge_save","knowledge_update" -> mutate(ctx,call,tool,args);
                case "knowledge_operation" -> operation(ctx.userId(),required(args,"operationRef",64));
                default -> throw new IllegalArgumentException("Unknown knowledge tool");
            };
            return json.writeValueAsString(response);
        } catch(Outcome e){return json.writeValueAsString(Map.of("status",e.status));
        } catch(IllegalArgumentException e){return json.writeValueAsString(Map.of("status","INVALID_ARGUMENT","message",Objects.toString(e.getMessage(),"Invalid input")));
        } catch(RuntimeException e){
            log.warn("Knowledge tool unavailable tool={} type={}",tool,e.getClass().getSimpleName());
            return json.writeValueAsString(Map.of("status","UNAVAILABLE"));
        }
    }
    private Document owned(long user,long id,boolean lock) {
        Document d=repo.owned(user,id,lock);if(d==null) throw new Outcome("NOT_FOUND");return d;
    }
    private Map<String,Object> catalog(long user,long cursor) {
        var rows=repo.catalog(user,cursor);boolean more=rows.size()>20;
        var page=rows.subList(0,Math.min(20,rows.size()));
        var result=new LinkedHashMap<String,Object>();result.put("status","OK");result.put("items",page);
        result.put("nextCursor",more?page.getLast().get("documentId"):null);return result;
    }
    private Map<String,Object> search(long user,Map<String,Object> args) {
        String query=required(args,"query",2000);
        var filter=metadataKey("userId").isEqualTo(Long.toString(user));
        Set<Long> selected=new LinkedHashSet<>();
        if(args.get("documentIds")!=null) {
            if(!(args.get("documentIds") instanceof List<?> ids) || ids.size()>20) throw new IllegalArgumentException("Invalid documentIds");
            for(Object id:ids){long n=Long.parseLong(id.toString());owned(user,n,false);selected.add(n);}
            if(selected.isEmpty()) return Map.of("status","OK","items",List.of(),"truncated",false);
            filter=filter.and(metadataKey("docId").isIn(selected.stream().map(String::valueOf).toList()));
        }
        var embedding=embeddings.embed(query).content();
        List<Map<String,Object>> hits=new ArrayList<>();boolean exhausted=false;
        int wanted=Math.max(1,Math.min(properties.getRetriever().getMaxResults(),12));
        // Old revisions and unavailable/orphan documents never become answers.
        for(int budget:new int[]{32,128,512}) {
            var found=vectors.search(EmbeddingSearchRequest.builder().queryEmbedding(embedding).filter(filter)
                    .maxResults(budget).minScore(properties.getRetriever().getMinScore()).build()).matches();
            hits.clear();int chars=0;
            for(var match:found) {
                TextSegment segment=match.embedded();if(segment==null) continue;
                if(!Long.toString(user).equals(segment.metadata().getString("userId"))) continue;
                String doc=segment.metadata().getString("docId"), revision=segment.metadata().getString("revision");
                long docId;
                try{docId=Long.parseLong(doc);}catch(RuntimeException invalid){continue;}
                Document d=repo.owned(user,docId,false);
                if(d==null || !"COMPLETED".equals(d.status()) || !Integer.toString(d.version()).equals(revision==null?"1":revision)) continue;
                if(!selected.isEmpty() && !selected.contains(d.id())) continue;
                String text=segment.text();int length=Math.min(text.length(),Math.min(4000,12000-chars));
                if(length<=0) break;
                var hit=documentView(d);hit.put("segmentId",match.embeddingId());hit.put("score",match.score());
                hit.put("segmentIndex",index(segment.metadata().getString("index")));hit.put("text",text.substring(0,length));
                hit.put("textTruncated",length<text.length());hit.put("sourceRef",reference(d.id(),d.version(),match.embeddingId()));
                hits.add(hit);chars+=length;if(hits.size()>=wanted || chars>=12000) break;
            }
            exhausted=found.size()<budget;
            if(hits.size()>=wanted || exhausted) break;
        }
        return Map.of("status","OK","items",hits,"truncated",!exhausted || hits.size()>=wanted);
    }
    private Map<String,Object> read(long user,Map<String,Object> args) {
        Document d=owned(user,id(args),false);
        if(!"COMPLETED".equals(d.status())) return Map.of("status","NOT_READY","documentId",d.id(),"documentStatus",d.status());
        int version=integer(args,"version",d.version());
        if(version<1 || (version!=d.version() && !repo.revisionExists(user,d.id(),version))) throw new Outcome("NOT_FOUND");
        int cursor=integer(args,"cursor",0);
        if(args.get("anchor")!=null) {
            Integer position=repo.position(user,d.id(),version,required(args,"anchor",128));
            if(position==null) throw new Outcome("NOT_FOUND");cursor=Math.max(0,position-2);
        }
        var chunks=repo.chunks(user,d.id(),version,cursor,9);
        var items=new ArrayList<Map<String,Object>>();int chars=0;
        for(var row:chunks.subList(0,Math.min(8,chunks.size()))) {
            Map<?,?> metadata=json.readValue(row.get("metadata").toString(),Map.class);
            String text=Objects.toString(row.get("text"),"");
            if(!items.isEmpty() && chars+text.length()>12000) break;
            var item=new LinkedHashMap<String,Object>();item.put("segmentId",row.get("segment_id"));
            item.put("segmentIndex",index(Objects.toString(metadata.get("index"),null)));
            item.put("text",text.substring(0,Math.min(text.length(),12000)));
            item.put("textTruncated",text.length()>12000);
            item.put("sourceRef",reference(d.id(),version,row.get("segment_id").toString()));
            items.add(item);chars+=Math.min(text.length(),12000);
        }
        var response=documentView(d);
        if(version!=d.version()) {
            var revision=repo.revision(user,d.id(),version);
            if(revision==null) throw new Outcome("NOT_FOUND");
            response.put("title",revision.get("title"));response.put("applicability",revision.get("applicability"));
            response.put("sourceRefs",json.readValue(revision.get("sourceRefs").toString(),List.class));
            response.put("actor",revision.get("actor"));response.put("createdAt",revision.get("createdAt"));
        }
        response.put("status","OK");response.put("version",version);response.put("items",items);
        response.put("orderAvailable",items.stream().allMatch(item->item.get("segmentIndex")!=null));
        response.put("nextCursor",chunks.size()>items.size()?cursor+items.size():null);return response;
    }
    private Map<String,Object> history(long user,long id,int cursor) {
        owned(user,id,false);var rows=repo.history(user,id,cursor);boolean more=rows.size()>20;
        var page=rows.subList(0,Math.min(rows.size(),20));
        for(var row:page) row.put("sourceRefs",json.readValue(Objects.toString(row.get("sourceRefs"),"[]"),List.class));
        var result=new LinkedHashMap<String,Object>();result.put("status","OK");result.put("documentId",id);result.put("items",page);
        result.put("historyAvailable",!page.isEmpty());result.put("nextCursor",more?page.getLast().get("version"):null);return result;
    }
    private Map<String,Object> documentView(Document d) {
        var view=new LinkedHashMap<String,Object>();view.put("documentId",d.id());view.put("title",d.title());
        view.put("sourceType",d.source());view.put("version",d.version());view.put("applicability",d.applicability());
        view.put("sourceRefs",json.readValue(d.sources(),List.class));view.put("url","/me/knowledge");return view;
    }
    private List<String> validateSources(AgentMemoryContext ctx,Map<String,Object> args) {
        var refs=new LinkedHashSet<>(sources.validated(ctx,List.of()));
        if(args.get("sourceRefs")!=null) {
            if(!(args.get("sourceRefs") instanceof List<?> list) || list.size()>8) throw new IllegalArgumentException("Invalid sourceRefs");
            for(Object value:list) {
                String ref=value.toString();
                if(ref.startsWith("knowledge:")) {
                    String[] parts=ref.split(":",4);if(parts.length!=4) throw new IllegalArgumentException("Invalid sourceRef");
                    Document d=owned(ctx.userId(),Long.parseLong(parts[1]),false);int version=Integer.parseInt(parts[2]);
                    if(!"COMPLETED".equals(d.status()) || (version!=d.version() && !repo.revisionExists(ctx.userId(),d.id(),version))
                            || repo.position(ctx.userId(),d.id(),version,parts[3])==null) throw new Outcome("SOURCE_UNAVAILABLE");
                } else if(!"OK".equals(sources.read(ctx.userId(),ref,0).get("status"))) throw new Outcome("SOURCE_UNAVAILABLE");
                refs.add(ref);
            }
        }
        return List.copyOf(refs);
    }
    private Map<String,Object> mutate(AgentMemoryContext ctx,String call,String tool,Map<String,Object> args) {
        if(call==null || call.isBlank()) throw new IllegalArgumentException("Missing trusted tool call ID");
        required(args,"title",120);required(args,"content",20000);optional(args,"applicability",4000);
        if(tool.equals("knowledge_update")) {id(args);if(integer(args,"expectedVersion",0)<1) throw new IllegalArgumentException("expectedVersion must be positive");}
        String key=hash(ctx.userId()+":"+ctx.sessionId()+":"+ctx.executionId()+":"+call);
        String hash=hash(tool+json.writeValueAsString(new TreeMap<>(args)));
        var op=repo.operation(key,false);
        if(op==null) repo.operation(key,ctx.userId(),hash,json.writeValueAsString(new Mutation(ctx,tool,args,validateSources(ctx,args),properties.getSplit().getChunkSize(),properties.getSplit().getChunkOverlap())));
        op=repo.operation(key,false);
        if(op.user()!=ctx.userId() || !op.hash().equals(hash)) throw new Outcome("CONFLICT");
        return advance(key);
    }
    private Map<String,Object> operation(long user,String key) {
        var op=repo.operation(key,false);if(op==null || op.user()!=user) throw new Outcome("NOT_FOUND");
        return op.result()==null?Map.of("status","PENDING","operationRef",key):json.readValue(op.result(),Map.class);
    }
    private Map<String,Object> advance(String key) {
        var op=repo.operation(key,false);
        if(op.result()!=null) return json.readValue(op.result(),Map.class);
        Mutation mutation=json.readValue(op.payload(),Mutation.class);
        String lease=UUID.randomUUID().toString();
        try {
            if(op.state().equals("NEW")) prepare(key,mutation);
            if(!repo.claim(key,lease)) return operation(op.user(),key);
            var claimed=repo.operation(key,false);
            if(claimed.document()==null) throw new Outcome("NOT_FOUND");
            Document d=owned(claimed.user(),claimed.document(),false);
            int version=claimed.targetVersion();
            String title=required(mutation.arguments(),"title",120),body=required(mutation.arguments(),"content",20000);
            String applicability=optional(mutation.arguments(),"applicability",4000);
            String indexed=title+"\n\n"+body+(applicability.isBlank()?"":"\n\n适用条件与限制：\n"+applicability);
            // No database transaction is held while embedding or upserting vectors.
            int count=indexer.index(claimed.user(),d.id(),version,title,indexed,mutation.chunkSize(),mutation.overlap());
            return repo.transaction(()->{
                var locked=repo.operation(key,true);
                if(locked.result()!=null) return json.readValue(locked.result(),Map.class);
                if(!lease.equals(locked.lease())) return Map.of("status","PENDING","operationRef",key);
                Document current=owned(locked.user(),d.id(),true);
                if(!key.equals(current.pending()) || current.version()!=version-1) throw new Outcome("CONFLICT");
                String refs=json.writeValueAsString(mutation.sources());var ctx=mutation.context();
                repo.jdbc.update("""
                    INSERT INTO knowledge_revisions(document_id,version,title,body,applicability,source_refs,actor,session_id,execution_id,operation_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """,d.id(),version,title,body,applicability,refs,Objects.toString(ctx.actor(),"agent"),ctx.sessionId(),ctx.executionId(),key);
                repo.jdbc.update("""
                    UPDATE knowledge_document SET file_name=?,body=?,applicability=?,source_refs=?,version=?,status='COMPLETED',
                        char_count=?,segment_count=?,pending_operation=NULL,error_msg=NULL,updated_at=NOW() WHERE id=? AND user_id=?
                    """,title,body,applicability,refs,version,body.length(),count,d.id(),locked.user());
                Map<String,Object> result=Map.of("status","OK","documentId",d.id(),"version",version,"segmentCount",count);
                repo.jdbc.update("UPDATE knowledge_operations SET state='COMPLETED',result=?,updated_at=NOW() WHERE id=?",json.writeValueAsString(result),key);
                return result;
            });
        } catch(Outcome e) {
            Map<String,Object> result=Map.of("status",e.status);
            repo.jdbc.update("UPDATE knowledge_operations SET state='FAILED',result=?,updated_at=NOW() WHERE id=? AND (state='NEW' OR lease_id=?)",json.writeValueAsString(result),key,lease);
            return operation(op.user(),key);
        } catch(RuntimeException e) {
            log.warn("Knowledge indexing pending operation={} type={}",key,e.getClass().getSimpleName());
            repo.jdbc.update("UPDATE knowledge_operations SET state='PREPARED',lease_id=NULL,updated_at=NOW() WHERE id=? AND state='INDEXING' AND lease_id=?",key,lease);
            return Map.of("status","PENDING","operationRef",key);
        }
    }
    private void prepare(String key,Mutation mutation) {
        repo.transaction(()->{
            var op=repo.operation(key,true);if(!op.state().equals("NEW")) return null;
            Long id;int targetVersion;
            if(mutation.tool().equals("knowledge_save")) {
                targetVersion=1;
                id=repo.jdbc.queryForObject("""
                    INSERT INTO knowledge_document(user_id,file_name,source_type,file_type,status,version,pending_operation)
                    VALUES (?,?,'AGENT','txt','PROCESSING',0,?) RETURNING id
                    """,Long.class,op.user(),required(mutation.arguments(),"title",120),key);
            } else {
                var d=owned(op.user(),id(mutation.arguments()),true);
                if(!"AGENT".equals(d.source())) throw new Outcome("READ_ONLY_DOCUMENT");
                if(d.version()!=integer(mutation.arguments(),"expectedVersion",0) || d.pending()!=null || !"COMPLETED".equals(d.status())) throw new Outcome("CONFLICT");
                targetVersion=d.version()+1;
                id=d.id();repo.jdbc.update("UPDATE knowledge_document SET pending_operation=? WHERE id=?",key,id);
            }
            repo.jdbc.update("UPDATE knowledge_operations SET state='PREPARED',document_id=?,target_version=?,updated_at=NOW() WHERE id=?",id,targetVersion,key);
            return null;
        });
    }
    @Scheduled(fixedDelayString="${knowledge.operation-reconcile-delay-ms:30000}")
    public void reconcile() {
        for(String key:repo.pending()) {
            try{advance(key);}catch(RuntimeException e){log.warn("Knowledge recovery pending operation={} type={}",key,e.getClass().getSimpleName());}
        }
    }
    private static String reference(long id,int version,String segment){return "knowledge:"+id+":"+version+":"+segment;}
    private static Integer index(String value){return value!=null && value.matches("[0-9]{1,9}")?Integer.valueOf(value):null;}
    private static String required(Map<String,Object> args,String key,int max) {
        String value=optional(args,key,max);if(value.isBlank()) throw new IllegalArgumentException(key+" is required");return value;
    }
    private static String optional(Map<String,Object> args,String key,int max) {
        String value=Objects.toString(args.get(key),"");if(value.length()>max) throw new IllegalArgumentException(key+" is too long");return value;
    }
    private static long number(Map<String,Object> args,String key,long fallback) {
        long n=args.get(key)==null?fallback:Long.parseLong(args.get(key).toString());
        if(n<0) throw new IllegalArgumentException("Invalid "+key);return n;
    }
    private static int integer(Map<String,Object> args,String key,int fallback){return Math.toIntExact(number(args,key,fallback));}
    private static long id(Map<String,Object> args){long n=number(args,"documentId",0);if(n<1) throw new IllegalArgumentException("Invalid documentId");return n;}
    private static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
