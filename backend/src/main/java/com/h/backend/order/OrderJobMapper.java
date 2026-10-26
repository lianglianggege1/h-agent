package com.h.backend.order;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface OrderJobMapper {
    record Job(String id,Long userId,String sessionId,String requestHash,String requestJson,String status,
               String resultJson,String errorMessage,int attempts,boolean operator) { }

    @Insert("INSERT INTO order_execution_jobs(id,user_id,session_id,request_hash,request_json,operator) VALUES(#{id},#{userId},#{sessionId},#{requestHash},#{requestJson},#{operator}) ON CONFLICT DO NOTHING")
    void insert(Job job);
    @Select("SELECT id,user_id,session_id,request_hash,request_json,status,result_json,error_message,attempts,operator FROM order_execution_jobs WHERE id=#{id} AND user_id=#{userId}")
    Job find(@Param("userId") long userId,@Param("id") String id);

    @Select(value="""
        UPDATE order_execution_jobs SET lease_until=NOW()+INTERVAL '5 minutes',attempts=attempts+1,updated_at=NOW()
        WHERE id=(SELECT j.id FROM order_execution_jobs j
          WHERE j.status IN ('PENDING','APPLIED') AND (j.lease_until IS NULL OR j.lease_until<NOW())
          AND NOT EXISTS(SELECT 1 FROM order_execution_jobs k WHERE k.user_id=j.user_id AND k.session_id=j.session_id
             AND k.id<>j.id AND k.status IN ('PENDING','APPLIED')
             AND (k.created_at<j.created_at OR (k.created_at=j.created_at AND k.id<j.id)))
          ORDER BY j.created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
        RETURNING id,user_id,session_id,request_hash,request_json,status,result_json,error_message,attempts,operator
        """,affectData=true)
    Job claim();
    @Update("UPDATE order_execution_jobs SET status=CASE WHEN status='CANCELLED' THEN status ELSE 'APPLIED' END,result_json=#{result},updated_at=NOW() WHERE id=#{id}")
    void applied(@Param("id")String id,@Param("result")String result);
    @Update("UPDATE order_execution_jobs SET status=CASE WHEN status='CANCELLED' THEN status ELSE #{status} END,error_message=#{error},lease_until=NULL,updated_at=NOW() WHERE id=#{id}")
    void finish(@Param("id")String id,@Param("status")String status,@Param("error")String error);
    @Update("UPDATE order_execution_jobs SET lease_until=NOW()+INTERVAL '5 seconds',attempts=GREATEST(attempts-1,0) WHERE id=#{id}")
    void defer(String id);
    @Update("UPDATE order_execution_jobs SET status='CANCELLED',lease_until=NULL,updated_at=NOW() WHERE user_id=#{userId} AND session_id=#{sessionId} AND status IN ('PENDING','APPLIED')")
    void cancel(@Param("userId")long userId,@Param("sessionId")String sessionId);

    @Insert("INSERT INTO order_chat_recovery(run_id,user_id,session_id,message) VALUES(#{runId},#{userId},#{sessionId},#{message}) ON CONFLICT DO NOTHING")
    void registerRun(@Param("runId")String runId,@Param("userId")long userId,@Param("sessionId")String sessionId,@Param("message")String message);
    @Update("UPDATE order_chat_recovery SET status=#{status},updated_at=NOW() WHERE run_id=#{runId} AND status<>'QUEUED'")
    void runStatus(@Param("runId")String runId,@Param("status")String status);
    @Update("UPDATE order_chat_recovery SET status='CANCELLED',updated_at=NOW() WHERE user_id=#{userId} AND session_id=#{sessionId} AND status IN ('RUNNING','FAILED','QUEUED')")
    void cancelRecovery(@Param("userId")long userId,@Param("sessionId")String sessionId);
    @Update("""
        WITH due AS (
          UPDATE order_chat_recovery SET status='QUEUED',updated_at=NOW()
          WHERE run_id=(SELECT r.run_id FROM order_chat_recovery r
            WHERE (r.status='FAILED' OR (r.status='RUNNING' AND r.updated_at<NOW()-INTERVAL '60 seconds'))
            AND NOT EXISTS(SELECT 1 FROM order_chat_recovery n WHERE n.user_id=r.user_id AND n.session_id=r.session_id AND n.created_at>r.created_at)
            ORDER BY r.created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
          RETURNING *)
        INSERT INTO order_execution_jobs(id,user_id,session_id,request_hash,request_json,operator)
        SELECT 'recover-'||md5(run_id),user_id,session_id,md5(message),
          json_build_object('commandId','recover-'||md5(run_id),'orderId','','action','RESUME_CHAT',
            'generation','','revision',0,'detail',message,
            'anchorMessageId',(SELECT m.id FROM chat_session_messages m
                WHERE m.user_id=due.user_id AND m.session_id=due.session_id
                  AND m.role_code='user' AND m.content_text=due.message AND m.created_at>=due.created_at
                ORDER BY m.id DESC LIMIT 1))::text,FALSE FROM due
        ON CONFLICT DO NOTHING
        """)
    void recoverStale();

    @Update("UPDATE order_execution_jobs SET status='CANCELLED',lease_until=NULL WHERE user_id=#{userId} AND session_id=#{sessionId} AND id LIKE 'recover-%' AND status IN ('PENDING','APPLIED')")
    void supersedeRecoveries(@Param("userId")long userId,@Param("sessionId")String sessionId);
}
