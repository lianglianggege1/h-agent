package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationProposal;
import com.h.backend.automation.domain.ExecutionSpec;
import com.h.capabilities.common.exception.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

@Service
public class AutomationProposalActions {
    private final AutomationProposalRepository proposals;
    private final AutomationProposalModule module;
    private final AutomationTaskService tasks;
    private final List<AutomationExecutionAdapter> adapters;
    private final JdbcClient jdbc;
    private final java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(4);

    public AutomationProposalActions(AutomationProposalRepository proposals, AutomationProposalModule module,
            AutomationTaskService tasks, List<AutomationExecutionAdapter> adapters, DataSource source) {
        this.proposals = proposals;
        this.module = module;
        this.tasks = tasks;
        this.adapters = adapters;
        this.jdbc = JdbcClient.create(source);
    }

    @Transactional
    public AutomationProposal edit(long owner, String id, Edit edit) {
        return editLocked(proposals.findOwnedForUpdate(owner, id).orElseThrow(this::missing), edit);
    }

    @Transactional
    public AutomationProposalModule.ProposalSnapshot editInSession(long owner, String session, String id, Edit edit) {
        var proposal = proposals.findOwnedForUpdate(owner, id).orElseThrow(this::missing);
        if (!session.equals(proposal.sourceSessionId())) throw missing();
        return module.snapshot(editLocked(proposal, edit));
    }

    private AutomationProposal editLocked(AutomationProposal proposal, Edit edit) {
        pending(proposal, edit.revision());
        long owner = proposal.userId();
        String id = proposal.id();
        var existing = module.readCommand(proposal.payloadJson());
        var replacement = new AutomationTaskCommand(existing.name(), edit.instruction(), existing.agentId(),
                existing.runtime(), edit.cronExpression(), edit.zoneId(), false,
                existing.deliverySink(), existing.deliverySessionId());
        String session = module.executionSession(proposal);
        if ("CREATE".equals(proposal.action()) && edit.executionSessionId() != null && !edit.executionSessionId().isBlank()) {
            session = edit.executionSessionId();
        }
        var valid = tasks.validateCommandForSession(owner, session, replacement);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE automation_proposals SET payload_json = ?, updated_at = ? WHERE id = ? AND user_id = ?")
                .params(module.writePayload(valid, session), now, id, owner).update();
        return proposals.findOwned(owner, id).orElseThrow(this::missing);
    }

    public Trial trial(long owner, String id, Instant revision) {
        var proposal = pending(proposals.findOwned(owner, id).orElseThrow(this::missing), revision);
        String session = module.executionSession(proposal);
        var command = tasks.validateCommandForSession(owner, session, module.readCommand(proposal.payloadJson()));
        var adapter = adapters.stream().filter(candidate -> candidate.runtime() == command.runtime())
                .findFirst().orElseThrow(() -> new BusinessException(50340, "没有可用的自动化执行器"));
        var trialId = UUID.randomUUID().toString();
        var version = LocalDateTime.ofInstant(proposal.updatedAt(), ZoneOffset.UTC);
        int created = jdbc.sql("""
                INSERT INTO automation_proposal_trials (id, proposal_id, user_id, session_id, proposal_revision,
                    title, status)
                SELECT ?, id, user_id, ?, updated_at, ?, 'QUEUED'
                FROM automation_proposals
                WHERE id = ? AND user_id = ? AND status = 'PENDING' AND updated_at = ?
                ON CONFLICT (proposal_id, proposal_revision) DO NOTHING
                """).params(trialId, session, command.name(), id, owner, version).update();
        if (created == 0) return jdbc.sql("""
                SELECT id, status, output, failure FROM automation_proposal_trials
                WHERE proposal_id = ? AND user_id = ? AND proposal_revision = ?
                """).params(id, owner, version).query(AutomationProposalActions::trialRow).optional()
                .orElseThrow(() -> new BusinessException(40938, "提案已经变化，请刷新后重试"));

        var now = Instant.now();
        var spec = new ExecutionSpec(proposal.id(), owner, command.name(), 1L, trialId, "TRIAL", now,
                command.instruction(), command.agentId(), session, command.runtime().name(),
                "NONE", null, 180, "SKIP", "SKIP", now);
        workers.submit(() -> run(trialId, spec, adapter));
        return new Trial(trialId, "QUEUED", null, null);
    }

    public Trial result(long owner, String run) {
        return jdbc.sql("SELECT id,status,output,failure FROM automation_proposal_trials WHERE user_id = ? AND id = ?")
                .params(owner, run).query(AutomationProposalActions::trialRow).optional()
                .orElseThrow(() -> new BusinessException(40404, "试跑记录不存在"));
    }

    private void run(String trialId, ExecutionSpec spec, AutomationExecutionAdapter adapter) {
        if (jdbc.sql("""
                UPDATE automation_proposal_trials SET status = 'RUNNING', updated_at = NOW()
                WHERE id = ? AND status = 'QUEUED'
                """).param(trialId).update() != 1) return;
        try {
            var result = adapter.execute(spec);
            finish(trialId, "SUCCEEDED", result.output(), null);
        } catch (RuntimeException failure) {
            finish(trialId, "FAILED", null, "试跑未能完成，请查看会话中的执行结果。");
        }
    }

    private void finish(String id, String status, String output, String failure) {
        jdbc.sql("""
                UPDATE automation_proposal_trials SET status = ?, output = LEFT(?, 20000), failure = ?, updated_at = NOW()
                WHERE id = ? AND status = 'RUNNING'
                """).params(java.util.Arrays.asList(status, output, failure, id)).update();
    }

    @Scheduled(fixedDelay = 60000)
    public void expireInterruptedTrials() {
        jdbc.sql("""
                UPDATE automation_proposal_trials SET status = 'FAILED', failure = '试跑执行中断或超时', updated_at = NOW()
                WHERE status IN ('QUEUED','RUNNING') AND updated_at < NOW() - INTERVAL '10 minutes'
                """).update();
    }

    @jakarta.annotation.PreDestroy
    public void close() { workers.shutdownNow(); }

    private static Trial trialRow(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Trial(row.getString("id"), row.getString("status"), row.getString("output"),
                row.getString("failure"));
    }

    private AutomationProposal pending(AutomationProposal proposal, Instant revision) {
        if (!"PENDING".equals(proposal.status()) || !proposal.expiresAt().isAfter(Instant.now())
                || !List.of("CREATE", "UPDATE").contains(proposal.action())
                || !proposal.updatedAt().equals(revision)) {
            throw new BusinessException(40938, "提案不可修改或已变化，请刷新后重试");
        }
        return proposal;
    }

    private BusinessException missing() { return new BusinessException(40404, "自动化提案不存在"); }

    public record Edit(Instant revision, String instruction, String cronExpression, String zoneId, String executionSessionId) {}
    public record Trial(String id, String status, String output, String failure) {}
}
