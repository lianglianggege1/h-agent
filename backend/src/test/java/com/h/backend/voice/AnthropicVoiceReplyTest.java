package com.h.backend.voice;
import com.h.backend.voice.infrastructure.AnthropicVoiceReply;
import com.h.backend.chat.infrastructure.config.ChatModelEnvironment;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AnthropicVoiceReplyTest {
    private AnthropicVoiceReply adapter(HttpServer server) {
        return new AnthropicVoiceReply(new ChatModelEnvironment("test-key","test-model",
                "http://127.0.0.1:"+server.getAddress().getPort(),""), HttpClient.newHttpClient(), new ObjectMapper());
    }
    @Test void cancellationBeforeFirstResponseTerminatesRequest() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/messages",exchange->{reached.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}finally{exchange.close();}});
        server.start();
        try {
            var terminal=new CompletableFuture<String>();
            var execution=adapter(server).prepare("prompt",List.of(UserMessage.from("hello")),s->fail("unexpected text"),terminal::complete);
            execution.start(); assertTrue(reached.await(3,TimeUnit.SECONDS));
            execution.cancel(); assertEquals("CANCELLED",terminal.get(3,TimeUnit.SECONDS));
        } finally {release.countDown();server.stop(0);}
    }
    @Test void forwardsTextAndRequiresExplicitTerminalFrame() throws Exception {
        for(boolean finish:List.of(false,true)){
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/messages",exchange->{
                byte[] body=("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"你好😀\"}}\n\n"
                    +(finish?"data: {\"type\":\"message_stop\"}\n\n":"")).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            }); server.start();
            try {
                var terminal=new CompletableFuture<String>();var text=new StringBuilder();
                var execution=adapter(server).prepare("prompt",List.of(UserMessage.from("hello")),text::append,terminal::complete);
                execution.start();assertEquals(finish?"GENERATED":"FAILED",terminal.get(3,TimeUnit.SECONDS));
                assertEquals("你好😀",text.toString());
            }finally{server.stop(0);}
        }
    }
}
