package com.h.backend.memory.infrastructure;

import com.h.backend.memory.application.*;
import com.h.backend.memory.domain.*;
import com.h.backend.memory.infrastructure.mem0.*;
import com.h.backend.memory.infrastructure.AgentMemoryStore.Memory;
import com.h.backend.memory.infrastructure.AgentMemoryStore.Operation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Model-directed operations. No automatic recall or extraction lives here. */
@Service
public class AgentMemoryService implements AgentMemory {
    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);
    private static final Set<String> STATUSES = Set.of("ACTIVE","COMPLETED","OBSOLETE","SUPERSEDED","DELETED");
    private final AgentMemoryStore store;
    private final ObjectProvider<Mem0Gateway> gateways;
    private final MemorySources sources;
    private final ObjectMapper json;
    public AgentMemoryService(AgentMemoryStore store, ObjectProvider<Mem0Gateway> gateways,
                              MemorySources sources, ObjectMapper json) {
        this.store=store; this.gateways=gateways; this.sources=sources; this.json=json;
    }
    public record Mutation(AgentMemoryContext context, String name, Map<String,Object> arguments, List<String> sources) {}
    private static final class Outcome extends RuntimeException {
        final String status;
        Outcome(String status) { super(status); this.status=status; }
    }
    private Mem0Gateway gateway() {
        Mem0Gateway g=gateways.getIfAvailable();
        if(g==null) throw new Outcome("UNAVAILABLE");
        return g;
    }
    @Override public String execute(AgentMemoryContext ctx,String call,String name,Map<String,Object> args) {
        try {
            if(ctx==null) throw new IllegalArgumentException("Missing execution identity");
            if(!name.equals("memory_read_source") && !(name.equals("memory_search") && "LEGACY".equals(args.get("source")))) gateway();
            Map<String,Object> result=switch(name) {
                case "memory_search" -> search(ctx,args);
                case "memory_get" -> get(ctx.userId(),id(args),integer(args,"revision",0),integer(args,"cursor",0));
                case "memory_operation" -> operation(ctx.userId(),text(args,"operationRef"));
                case "memory_history" -> history(ctx.userId(),id(args),integer(args,"cursor",0));
                case "memory_read_source" -> sources.read(ctx.userId(),text(args,"sourceRef"),integer(args,"cursor",0));
                case "memory_save", "memory_update", "memory_supersede", "memory_set_status" -> mutate(ctx,call,name,args);
                default -> throw new IllegalArgumentException("Unknown memory tool");
            };
            return json.writeValueAsString(result);
        } catch(Outcome e) { return json.writeValueAsString(Map.of("status",e.status));
        } catch(IllegalArgumentException e) { return json.writeValueAsString(Map.of("status","INVALID_ARGUMENT","message",Objects.toString(e.getMessage(),"Invalid input")));
        } catch(com.h.backend.common.exception.BusinessException e) { return json.writeValueAsString(Map.of("status","NOT_FOUND"));
        } catch(RuntimeException e) {
            log.warn("Memory tool unavailable name={} type={}",name,e.getClass().getSimpleName());
            return json.writeValueAsString(Map.of("status","UNAVAILABLE"));
        }
    }
    private Map<String,Object> search(AgentMemoryContext ctx,Map<String,Object> a) {
        String query=text(a,"query");
        String source=option(a,"source","MEM0",Set.of("MEM0","LEGACY"));
        if(source.equals("LEGACY")) return sources.search(ctx.userId(),query);
        Set<String> scopes=options(a,"scopes",Set.of("USER","AGENT","SESSION"),Set.of("USER","AGENT","SESSION"));
        Set<String> statuses=options(a,"statuses",Set.of("ACTIVE"),STATUSES);
        String range=option(a,"sessionRange","CURRENT",Set.of("CURRENT","HISTORICAL","ALL"));
        if(scopes.isEmpty() || (scopes.equals(Set.of("AGENT")) && ctx.stableAgentId()==null))
            return Map.of("status","OK","items",List.of(),"truncated",false);
        Map<String,Object> filters=new LinkedHashMap<>();
        if(scopes.equals(Set.of("USER"))) filters.put("scope_kind","USER");
        if(scopes.equals(Set.of("AGENT"))) {
            filters.put("scope_kind","AGENT"); filters.put("agent_id",MemoryScopePolicy.AGENT_ID_PREFIX+ctx.stableAgentId());
        }
        if(scopes.equals(Set.of("SESSION")) && range.equals("CURRENT"))
            filters.put("run_id",MemoryScopePolicy.RUN_ID_PREFIX+ctx.rootSessionId());
        List<Map<String,Object>> items=new ArrayList<>();
        boolean exhausted=false;
        // Bounded expansion prevents filtered historical vectors from silently swallowing every hit.
        for(int topK: new int[]{40,120,400}) {
            var candidates=gateway().searchFiltered(MemoryScopePolicy.USER_ID_PREFIX+ctx.userId(),query,topK,filters);
            items.clear();
            for(var candidate:candidates) {
                Memory m=store.remote(ctx.userId(),candidate.id());
                if(m==null || m.pending()!=null || !statuses.contains(m.status()) || !matches(ctx,m,scopes,range)) continue;
                if(candidate.text()==null) continue;
                int used=items.stream().mapToInt(item->item.get("text").toString().length()).sum();
                if(used>=16000) break;
                String excerpt=candidate.text().substring(0,Math.min(candidate.text().length(),Math.min(8000,16000-used)));
                Map<String,Object> item=view(m,excerpt);item.put("textTruncated",excerpt.length()<candidate.text().length());
                items.add(item);
                if(items.size()==20) break;
            }
            exhausted=candidates.size()<topK;
            if(items.size()==20 || exhausted) break;
        }
        return Map.of("status","OK","items",items,"truncated",!exhausted || items.size()==20 || items.stream().mapToInt(item->item.get("text").toString().length()).sum()>=16000);
    }
    static boolean matches(AgentMemoryContext ctx,Memory m,Set<String> scopes,String range) {
        if(m.user()!=ctx.userId()) return false;
        return switch(m.scope()) {
            case "USER" -> scopes.contains("USER");
            case "AGENT" -> scopes.contains("AGENT") && ctx.stableAgentId()!=null && ctx.stableAgentId().equals(m.agent());
            case "SESSION","RUN" -> m.session()!=null && scopes.contains("SESSION") && switch(range) {
                case "ALL" -> true;
                case "HISTORICAL" -> !ctx.rootSessionId().equals(m.session());
                default -> ctx.rootSessionId().equals(m.session());
            };
            default -> false;
        };
    }
    private Memory require(long user,long id,boolean lock) {
        Memory m=store.owned(user,id,lock);
        if(m==null || m.version()<1) throw new Outcome("NOT_FOUND");
        return m;
    }
    private Map<String,Object> operation(long user,String key) {
        Operation op=store.operation(key,false);
        if(op==null || op.user()!=user) throw new Outcome("NOT_FOUND");
        return op.result()==null?pending(key):json.readValue(op.result(),Map.class);
    }
    private Map<String,Object> excerpt(String body,int cursor) {
        if(cursor<0) throw new IllegalArgumentException("Invalid cursor");
        int start=Math.min(cursor,body.length()), end=Math.min(body.length(),start+8000);
        Map<String,Object> page=new LinkedHashMap<>();page.put("text",body.substring(start,end));
        page.put("nextCursor",end<body.length()?end:null);return page;
    }
    private Map<String,Object> get(long user,long id,int revision,int cursor) {
        Memory m=require(user,id,false);
        if(revision>0) {
            var old=store.revision(id,revision);
            if(old==null) throw new Outcome("NOT_FOUND");
            Map<String,Object> result=new LinkedHashMap<>(old);
            result.remove("body");result.putAll(excerpt(Objects.toString(old.get("body"),""),cursor));
            return Map.of("status","OK","memoryId",id,"revision",result);
        }
        if(m.pending()!=null) return pending(m.pending());
        if(!m.status().equals("ACTIVE")) return Map.of("status","INACTIVE","memoryId",id,"memoryStatus",m.status(),"version",m.version());
        Map<String,Object> item=view(m,"");item.putAll(excerpt(body(m),cursor));
        return Map.of("status","OK","item",item);
    }
    private Map<String,Object> history(long user,long id,int cursor) {
        require(user,id,false);
        if(cursor<0) throw new IllegalArgumentException("Invalid cursor");
        var rows=store.history(id,cursor);
        boolean more=rows.size()>20;
        var page=rows.subList(0,Math.min(20,rows.size()));
        Map<String,Object> result=new LinkedHashMap<>();
        List<Map<String,Object>> previews=new ArrayList<>();
        for(var revision:page) {
            var preview=new LinkedHashMap<>(revision);
            String body=Objects.toString(preview.get("body"),"");
            preview.put("body",body.substring(0,Math.min(body.length(),800)));
            preview.put("textTruncated",body.length()>800);previews.add(preview);
        }
        result.put("status","OK");result.put("memoryId",id);result.put("items",previews);
        result.put("nextCursor",more?page.getLast().get("version"):null);
        result.put("legacyHistoryUnavailable",rows.isEmpty());
        return result;
    }
    private Map<String,Object> view(Memory m,String text) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("memoryId",m.id());result.put("text",text);result.put("scope",m.scope().equals("RUN")?"SESSION":m.scope());
        result.put("updatedAt",m.updatedAt());
        result.put("version",m.version());result.put("memoryStatus",m.status());result.put("successorId",m.successor());
        result.put("sourceRefs",json.readValue(m.sources(),List.class));
        result.put("legacyScope",m.scope().equals("RUN"));
        return result;
    }
    private MemoryScopePolicy.MemoryOwnerScope scope(Memory m) {
        return MemoryScopePolicy.toOwnerScope(m.user(),MemoryScopeKind.valueOf(m.scope()),m.agent(),m.session());
    }
    private String body(Memory m) {
        var revision=store.revision(m.id(),m.version());
        if(revision!=null) return Objects.toString(revision.get("body"),"");
        var remote=gateway().get(m.remoteId(),scope(m));
        if(remote==null || remote.text()==null) throw new Outcome("UNAVAILABLE");
        return remote.text();
    }
    private Map<String,Object> mutate(AgentMemoryContext ctx,String call,String name,Map<String,Object> args) {
        gateway();
        if(call==null || call.isBlank()) throw new IllegalArgumentException("Missing trusted tool call ID");
        if(!name.equals("memory_set_status")) {
            String text=text(args,"text");
            if(text.length()>8000) throw new IllegalArgumentException("Memory exceeds 8000 characters");
        }
        if(name.equals("memory_save")) {
            String scope=option(args,"scope",null,Set.of("USER","AGENT","SESSION"));
            if(scope.equals("AGENT") && ctx.stableAgentId()==null) throw new IllegalArgumentException("Ephemeral agent has no AGENT scope");
        } else {
            id(args);
            if(integer(args,"expectedVersion",0)<1) throw new IllegalArgumentException("expectedVersion must be positive");
        }
        if(name.equals("memory_set_status")) option(args,"status",null,Set.of("ACTIVE","COMPLETED","OBSOLETE","DELETED"));
        String key=MemoryHashes.sha256Hex(ctx.userId()+":"+ctx.sessionId()+":"+ctx.executionId()+":"+call);
        // Identity and model arguments must replay exactly. Do not revalidate sources on replay.
        String hash=MemoryHashes.sha256Hex(name+json.writeValueAsString(new TreeMap<>(args)));
        Operation existing=store.operation(key,false);
        if(existing==null) {
            List<String> refs=sources.validated(ctx,strings(args.get("sourceRefs")));
            store.createOperation(key,ctx.userId(),hash,json.writeValueAsString(new Mutation(ctx,name,args,refs)));
        }
        Operation op=store.operation(key,false);
        if(op.user()!=ctx.userId() || !hash.equals(op.hash())) throw new Outcome("CONFLICT");
        return advance(key,false);
    }
    private Map<String,Object> advance(String key,boolean recovery) {
        Operation op=store.operation(key,false);
        if(op.result()!=null) return json.readValue(op.result(),Map.class);
        Mutation request=json.readValue(op.payload(),Mutation.class);
        try {
            if(op.state().equals("NEW")) prepare(key,request);
            op=store.operation(key,false);
            boolean remoteWrite=!request.name().equals("memory_set_status");
            if(remoteWrite && store.claim(key)) {
                try {
                    Memory m=store.owned(op.user(),op.memoryId(),false);
                    String text=text(request.arguments(),"text");
                    Map<String,Object> metadata=Map.of("scope_kind",m.scope(),"operation_key",key,"app","h-agent","source","agent_tool");
                    var added=gateway().add(new Mem0Models.Mem0AddCommand(scope(m),List.of(Mem0Models.Mem0Message.user(text)),false,metadata));
                    if(added.memoryIds().size()!=1) throw new IllegalStateException("Expected one memory for one explicit fact");
                    String remote=added.memoryIds().getFirst();
                    store.confirmed(key,remote);
                } catch(HttpClientErrorException e) {
                    Map<String,Object> result=Map.of("status","UNAVAILABLE");
                    store.transaction(() -> { store.reject(key,json.writeValueAsString(result)); return null; });
                    return result;
                } catch(RuntimeException e) { store.uncertain(key); return pending(key); }
            } else if(remoteWrite && Set.of("SENDING","UNKNOWN").contains(op.state())) {
                if(!recovery) return pending(key);
                // An uncertain create is never blindly resent. Exact metadata filtering can find its result.
                var found=gateway().findByOperationKey(MemoryScopePolicy.USER_ID_PREFIX+op.user(),key,text(request.arguments(),"text"));
                if(found.size()!=1) return pending(key);
                store.confirmed(key,found.getFirst().id());
            }
            op=store.operation(key,false);
            if(remoteWrite && !op.state().equals("CONFIRMED")) return pending(key);
            return finish(key,request);
        } catch(Outcome e) {
            Map<String,Object> result=Map.of("status",e.status);
            // Validation/conflicts before a remote write are definitive, not pending.
            Operation latest=store.operation(key,false);
            if(latest.state().equals("NEW")) store.reject(key,json.writeValueAsString(result));
            return result;
        }
    }
    private void prepare(String key,Mutation request) {
        store.transaction(() -> {
            Operation op=store.operation(key,true);
            if(!op.state().equals("NEW")) return null;
            AgentMemoryContext ctx=request.context();
            long id;
            if(request.name().equals("memory_save")) {
                id=store.reserveNew(ctx,text(request.arguments(),"scope"),key,json.writeValueAsString(request.sources()));
            } else {
                Memory m=require(ctx.userId(),id(request.arguments()),true);
                if(m.version()!=integer(request.arguments(),"expectedVersion",0) || m.pending()!=null) throw new Outcome("CONFLICT");
                if(!request.name().equals("memory_set_status") && !m.status().equals("ACTIVE")) throw new Outcome("CONFLICT");
                if(request.name().equals("memory_set_status") && text(request.arguments(),"status").equals("ACTIVE") && m.successor()!=null)
                    throw new Outcome("CONFLICT");
                // Import a verifiable baseline before the first mutation of a legacy record.
                store.revision(m,body(m),ctx.actor(),key);
                store.reserve(m,key); id=m.id();
            }
            store.prepared(key,id);
            return null;
        });
    }
    private Map<String,Object> finish(String key,Mutation request) {
        return store.transaction(() -> {
            Operation op=store.operation(key,true);
            if(op.result()!=null) return json.readValue(op.result(),Map.class);
            Memory m=store.owned(op.user(),op.memoryId(),true);
            if(!key.equals(m.pending())) throw new Outcome("CONFLICT");
            String refs=json.writeValueAsString(mergeSources(m.sources(),request.sources()));
            String status=request.name().equals("memory_set_status")?text(request.arguments(),"status"):"ACTIVE";
            String text=request.name().equals("memory_set_status")?body(m):text(request.arguments(),"text");
            String remote=op.remoteId()==null?m.remoteId():op.remoteId();
            long resultId=m.id();
            if(request.name().equals("memory_supersede")) {
                var ctx=request.context();
                var owner=new AgentMemoryContext(ctx.userId(),m.session()==null?ctx.rootSessionId():m.session(),ctx.sessionId(),m.agent(),ctx.actor(),ctx.sourceMessageId(),ctx.executionId());
                resultId=store.reserveNew(owner,m.scope(),key,refs);
                store.finishRecord(resultId,remote,1,"ACTIVE",null,refs);
                store.revision(store.owned(op.user(),resultId,false),text,ctx.actor(),key);
                store.finishRecord(m.id(),m.remoteId(),m.version()+1,"SUPERSEDED",resultId,m.sources());
                store.revision(store.owned(op.user(),m.id(),false),body(m),ctx.actor(),key);
            } else {
                store.finishRecord(m.id(),remote,m.version()+1,status,m.successor(),refs);
                store.revision(store.owned(op.user(),m.id(),false),text,request.context().actor(),key);
            }
            Memory committed=store.owned(op.user(),resultId,false);
            Map<String,Object> result=Map.of("status","OK","memoryId",resultId,"version",committed.version(),"memoryStatus",committed.status());
            store.complete(key,json.writeValueAsString(result));
            return result;
        });
    }
    private List<String> mergeSources(String old,List<String> added) {
        LinkedHashSet<String> all=new LinkedHashSet<>(json.readValue(old,List.class));all.addAll(added);
        return List.copyOf(all);
    }
    @Scheduled(fixedDelayString="${memory.long-term.explicit-reconcile-delay-ms:30000}")
    public void reconcile() {
        if(gateways.getIfAvailable()==null) return;
        for(String key:store.pending()) {
            try { advance(key,true); }
            catch(RuntimeException e) { log.warn("Memory operation reconciliation pending operation={} type={}",key,e.getClass().getSimpleName()); }
        }
    }
    private static Map<String,Object> pending(String key) { return Map.of("status","PENDING","operationRef",key); }
    private static long id(Map<String,Object> a) {
        long value=Long.parseLong(text(a,"memoryId"));
        if(value<1) throw new IllegalArgumentException("Invalid memory ID");return value;
    }
    private static String text(Map<String,Object> a,String key) {
        Object value=a.get(key);
        if(value==null || value.toString().isBlank()) throw new IllegalArgumentException(key+" is required");
        String s=value.toString();
        if(s.length()>10000) throw new IllegalArgumentException(key+" is too long");return s;
    }
    private static int integer(Map<String,Object> a,String key,int fallback) {
        return a.get(key)==null?fallback:Integer.parseInt(a.get(key).toString());
    }
    private static String option(Map<String,Object> a,String key,String fallback,Set<String> allowed) {
        String value=a.get(key)==null?fallback:a.get(key).toString();
        if(value==null || !allowed.contains(value)) throw new IllegalArgumentException("Invalid "+key);return value;
    }
    private static List<String> strings(Object value) {
        if(value==null) return List.of();
        if(!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected a list");
        return list.stream().map(String::valueOf).toList();
    }
    private static Set<String> options(Map<String,Object> a,String key,Set<String> fallback,Set<String> allowed) {
        if(a.get(key)==null) return fallback;
        Set<String> values=new LinkedHashSet<>(strings(a.get(key)));
        if(!allowed.containsAll(values)) throw new IllegalArgumentException("Invalid "+key);return values;
    }
}
