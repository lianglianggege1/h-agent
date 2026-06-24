package com.h.backend.chat.agent;

import com.h.backend.chat.dto.AgentTopologyDto;
import dev.langchain4j.agentic.planner.AgentArgument;
import dev.langchain4j.agentic.planner.Action;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.agentic.planner.Planner;
import dev.langchain4j.agentic.planner.PlanningContext;
import dev.langchain4j.agentic.workflow.ConditionalAgent;
import dev.langchain4j.agentic.workflow.ConditionalAgentInstance;
import dev.langchain4j.agentic.workflow.LoopAgentInstance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTopologyMapperTest {

    @Test
    void mapsSequenceWithChildrenAndStateKeys() {
        MockAgent child = MockAgent.ai(
                "extract",
                "Extract",
                "customerInfo",
                List.of(
                        new AgentArgument(String.class, "@MemoryId"),
                        new AgentArgument(String.class, "message")
                )
        );
        MockAgent root = MockAgent.sequence("root", "Root", "response", List.of(child));
        AgentDefinition definition = new AgentDefinition(
                "car",
                "Car Agent",
                "出行",
                List.of("rental"),
                "summary",
                root,
                AgentRuntimeType.AGENTIC_SYNC,
                true
        );

        AgentTopologyDto dto = new AgentTopologyMapper().from(definition, root);

        assertEquals("car", dto.agent().agentId());
        assertEquals("Car Agent", dto.agent().displayName());
        assertEquals("SEQUENCE", dto.root().topology());
        assertEquals("String", dto.root().returnType());
        assertEquals("MockPlanner", dto.root().plannerType());
        assertEquals("response", dto.root().outputKey());
        assertEquals(1, dto.root().children().size());
        assertEquals("customerInfo", dto.root().children().getFirst().outputKey());
        assertEquals(List.of("message"), dto.root().children().getFirst().inputKeys());
        assertEquals(List.of("response", "message", "customerInfo"), dto.stateKeys().stream()
                .map(key -> key.key())
                .toList());
        assertEquals("String", dto.stateKeys().getFirst().type());
        assertNotNull(dto.stateKeys().getFirst().color());
        assertTrue(dto.stateKeys().getFirst().color().startsWith("#"));
    }

    @Test
    void mapsRouterConditionsOntoChildren() {
        MockAgent fire = MockAgent.ai("fire", "Fire", "fireResponse", List.of());
        MockAgent medical = MockAgent.ai("medical", "Medical", "medicalResponse", List.of());
        MockRouter router = new MockRouter("router", "Router", List.of(
                new ConditionalAgent("has fire emergency", scope -> true, List.of(fire)),
                new ConditionalAgent("has medical emergency", scope -> true, List.of(medical))
        ));
        AgentDefinition definition = new AgentDefinition(
                "car",
                "Car Agent",
                "出行",
                List.of(),
                "summary",
                router,
                AgentRuntimeType.AGENTIC_SYNC,
                true
        );

        AgentTopologyDto dto = new AgentTopologyMapper().from(definition, router);

        assertEquals("ROUTER", dto.root().topology());
        assertEquals("has fire emergency", dto.root().children().get(0).condition());
        assertEquals("has medical emergency", dto.root().children().get(1).condition());
    }

    @Test
    void mapsLoopMetadata() {
        MockAgent child = MockAgent.ai("attempt", "Attempt", "attemptResult", List.of());
        MockLoop loop = new MockLoop("loop", "Loop", 4, "done == true", false, List.of(child));
        AgentDefinition definition = new AgentDefinition(
                "car",
                "Car Agent",
                "出行",
                List.of(),
                "summary",
                loop,
                AgentRuntimeType.AGENTIC_SYNC,
                true
        );

        AgentTopologyDto dto = new AgentTopologyMapper().from(definition, loop);

        assertEquals("LOOP", dto.root().topology());
        assertEquals(4, dto.root().loop().maxIterations());
        assertEquals("done == true", dto.root().loop().exitCondition());
        assertFalse(dto.root().loop().testExitAtLoopEnd());
        assertNull(dto.root().children().getFirst().loop());
    }

    static class MockAgent implements AgentInstance {
        private final String agentId;
        private final String name;
        private final AgenticSystemTopology topology;
        private final String outputKey;
        private final List<AgentInstance> children;
        private final List<AgentArgument> arguments;

        static MockAgent sequence(String agentId, String name, String outputKey, List<AgentInstance> children) {
            return new MockAgent(agentId, name, AgenticSystemTopology.SEQUENCE, outputKey, children, List.of());
        }

        static MockAgent ai(String agentId, String name, String outputKey, List<AgentArgument> arguments) {
            return new MockAgent(agentId, name, AgenticSystemTopology.AI_AGENT, outputKey, List.of(), arguments);
        }

        MockAgent(
                String agentId,
                String name,
                AgenticSystemTopology topology,
                String outputKey,
                List<AgentInstance> children,
                List<AgentArgument> arguments
        ) {
            this.agentId = agentId;
            this.name = name;
            this.topology = topology;
            this.outputKey = outputKey;
            this.children = children;
            this.arguments = arguments;
        }

        @Override
        public Class<?> type() {
            return String.class;
        }

        @Override
        public Class<? extends Planner> plannerType() {
            return MockPlanner.class;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String agentId() {
            return agentId;
        }

        @Override
        public String description() {
            return name + " desc";
        }

        @Override
        public Type outputType() {
            return String.class;
        }

        @Override
        public String outputKey() {
            return outputKey;
        }

        @Override
        public boolean async() {
            return false;
        }

        @Override
        public List<AgentArgument> arguments() {
            return arguments;
        }

        @Override
        public AgentInstance parent() {
            return null;
        }

        @Override
        public List<AgentInstance> subagents() {
            return children;
        }

        @Override
        public AgenticSystemTopology topology() {
            return topology;
        }
    }

    static class MockPlanner implements Planner {
        @Override
        public Action nextAction(PlanningContext planningContext) {
            return done();
        }
    }

    static class MockRouter extends MockAgent implements ConditionalAgentInstance {
        private final List<ConditionalAgent> conditionalSubagents;

        MockRouter(String agentId, String name, List<ConditionalAgent> conditionalSubagents) {
            super(
                    agentId,
                    name,
                    AgenticSystemTopology.ROUTER,
                    null,
                    conditionalSubagents.stream()
                            .flatMap(conditionalAgent -> conditionalAgent.agentInstances().stream())
                            .toList(),
                    List.of()
            );
            this.conditionalSubagents = conditionalSubagents;
        }

        @Override
        public List<ConditionalAgent> conditionalSubagents() {
            return conditionalSubagents;
        }

        @Override
        public <T extends AgentInstance> T as(Class<T> agentType) {
            if (agentType.isInstance(this)) {
                return agentType.cast(this);
            }
            throw new ClassCastException("Cannot cast to " + agentType.getName());
        }
    }

    static class MockLoop extends MockAgent implements LoopAgentInstance {
        private final int maxIterations;
        private final String exitCondition;
        private final boolean testExitAtLoopEnd;

        MockLoop(
                String agentId,
                String name,
                int maxIterations,
                String exitCondition,
                boolean testExitAtLoopEnd,
                List<AgentInstance> children
        ) {
            super(agentId, name, AgenticSystemTopology.LOOP, null, children, List.of());
            this.maxIterations = maxIterations;
            this.exitCondition = exitCondition;
            this.testExitAtLoopEnd = testExitAtLoopEnd;
        }

        @Override
        public int maxIterations() {
            return maxIterations;
        }

        @Override
        public boolean testExitAtLoopEnd() {
            return testExitAtLoopEnd;
        }

        @Override
        public String exitCondition() {
            return exitCondition;
        }

        @Override
        public <T extends AgentInstance> T as(Class<T> agentType) {
            if (agentType.isInstance(this)) {
                return agentType.cast(this);
            }
            throw new ClassCastException("Cannot cast to " + agentType.getName());
        }
    }
}
