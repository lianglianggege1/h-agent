-- 旧版将像素覆盖疑点直接标记为失败。仅重试尚无人工决定、未取消的此类任务。
-- 复用已保存的 fileId/jobId 重新读取 OCR 结果并执行 Jev，绝不直接放行历史草稿。
WITH candidates AS (
    SELECT id, state_json::jsonb AS state
    FROM chat_document_tasks
    WHERE status = 'VERIFICATION_FAILED'
      AND COALESCE(state_json::jsonb->>'continuationCancelled', 'false') = 'false'
      AND EXISTS (
          SELECT 1 FROM jsonb_array_elements(state_json::jsonb->'pages') AS p,
              jsonb_array_elements(p->'blocks') AS b
          WHERE b->>'verdict' = 'COVERAGE_ERROR'
      )
      AND NOT EXISTS (
          SELECT 1 FROM jsonb_array_elements(state_json::jsonb->'pages') AS p,
              jsonb_array_elements(p->'blocks') AS b
          WHERE b->>'decision' IS NOT NULL
      )
), repaired AS (
    SELECT id, state || jsonb_build_object(
        'status', 'QUEUED', 'detail', '覆盖策略已更新，正在重新复核',
        'resumeStatus', 'NONE', 'retries', 0, 'spentMillis', 0, 'lastWorkAt', 0,
        'pages', (SELECT jsonb_agg(CASE WHEN p->>'quality' = 'GOOD' THEN p
                    ELSE jsonb_set(p, '{blocks}', '[]'::jsonb) END ORDER BY ordinal)
                  FROM jsonb_array_elements(state->'pages') WITH ORDINALITY AS pages(p, ordinal))
    ) AS state
    FROM candidates
)
UPDATE chat_document_tasks AS tasks
SET status = 'QUEUED', state_json = repaired.state::text, revision = tasks.revision + 1,
    lease_until = NULL, updated_at = NOW()
FROM repaired WHERE tasks.id = repaired.id;
